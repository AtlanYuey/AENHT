"""Reads an ncnn model (.param + .bin), runs it with numpy (reference), and
builds a Core ML program from the same graph. Supports the layers of
Real-ESRGAN's ncnn models: Input, Split, Convolution (+ leaky ReLU),
PReLU, Concat, Eltwise (sum with coefficients), BinaryOp (add), Interp
(nearest), PixelShuffle."""
import struct
import numpy as np


class Layer:
    def __init__(self, kind, name, bottoms, tops, params):
        self.kind, self.name, self.bottoms, self.tops, self.params = kind, name, bottoms, tops, params
        self.weights = {}


def parse_params(tokens):
    params = {}
    for tok in tokens:
        key, value = tok.split('=', 1)
        key = int(key)
        if key <= -23300:  # array: -23300 - id = count,v1,v2…
            items = value.split(',')
            params[-(key + 23300)] = [float(x) for x in items[1:]]
        else:
            params[key] = float(value) if ('.' in value or 'e' in value) else int(value)
    return params


def load(param_path, bin_path):
    lines = open(param_path).read().split('\n')
    assert lines[0].strip() == '7767517'
    layers = []
    for line in lines[2:]:
        parts = line.split()
        if not parts:
            continue
        kind, name, nb, nt = parts[0], parts[1], int(parts[2]), int(parts[3])
        bottoms = parts[4:4 + nb]
        tops = parts[4 + nb:4 + nb + nt]
        layers.append(Layer(kind, name, bottoms, tops, parse_params(parts[4 + nb + nt:])))
    data = open(bin_path, 'rb').read()
    pos = 0

    def take_tagged(count):
        nonlocal pos
        tag = struct.unpack_from('<I', data, pos)[0]
        pos += 4
        if tag == 0x01306B47:  # float16
            arr = np.frombuffer(data, np.float16, count, pos).astype(np.float32)
            pos += count * 2
            pos = (pos + 3) // 4 * 4
        elif tag == 0:
            arr = np.frombuffer(data, np.float32, count, pos).copy()
            pos += count * 4
        else:
            raise ValueError(f'weight tag {tag:#x}')
        return arr

    def take_raw(count):
        nonlocal pos
        arr = np.frombuffer(data, np.float32, count, pos).copy()
        pos += count * 4
        return arr

    for l in layers:
        if l.kind == 'Convolution':
            out = l.params[0]
            k = l.params.get(1, 1)
            size = l.params[6]
            w = take_tagged(size)
            l.weights['w'] = w.reshape(out, size // (out * k * k), k, k)
            if l.params.get(5, 0):
                l.weights['b'] = take_raw(out)
        elif l.kind == 'PReLU':
            l.weights['slope'] = take_raw(l.params.get(0, 1))
    assert pos == len(data), (pos, len(data))
    return layers


# ---------------- numpy reference (CHW, batch 1) ----------------

def conv(x, w, b, pad):
    _, h, wd = x.shape
    o, i, kh, kw = w.shape
    xp = np.pad(x, ((0, 0), (pad, pad), (pad, pad)))
    out = np.zeros((o, h, wd), np.float32)
    for dy in range(kh):
        for dx in range(kw):
            patch = xp[:, dy:dy + h, dx:dx + wd].reshape(i, -1)
            out += (w[:, :, dy, dx] @ patch).reshape(o, h, wd)
    if b is not None:
        out += b[:, None, None]
    return out


def run_numpy(layers, image):
    blobs = {}
    for l in layers:
        p = l.params
        if l.kind == 'Input':
            blobs[l.tops[0]] = image
        elif l.kind == 'Split':
            for t in l.tops:
                blobs[t] = blobs[l.bottoms[0]]
        elif l.kind == 'Convolution':
            y = conv(blobs[l.bottoms[0]], l.weights['w'], l.weights.get('b'), p.get(4, 0))
            act = p.get(9, 0)
            if act == 1:
                y = np.maximum(y, 0)
            elif act == 2:
                y = np.where(y > 0, y, y * p[10][0])
            blobs[l.tops[0]] = y
        elif l.kind == 'PReLU':
            x = blobs[l.bottoms[0]]
            s = l.weights['slope'][:, None, None]
            blobs[l.tops[0]] = np.where(x > 0, x, x * s)
        elif l.kind == 'Concat':
            blobs[l.tops[0]] = np.concatenate([blobs[b] for b in l.bottoms], 0)
        elif l.kind == 'Eltwise':
            assert p.get(0) == 1
            coeffs = p.get(1, [1.0] * len(l.bottoms))
            blobs[l.tops[0]] = sum(c * blobs[b] for c, b in zip(coeffs, l.bottoms))
        elif l.kind == 'BinaryOp':
            assert p.get(0, 0) == 0
            blobs[l.tops[0]] = blobs[l.bottoms[0]] + blobs[l.bottoms[1]]
        elif l.kind == 'Interp':
            assert p.get(0) == 1, 'nearest only'
            s = int(round(p[1]))
            blobs[l.tops[0]] = blobs[l.bottoms[0]].repeat(s, 1).repeat(s, 2)
        elif l.kind == 'PixelShuffle':
            r = p[0]
            x = blobs[l.bottoms[0]]
            c, h, w = x.shape
            blobs[l.tops[0]] = x.reshape(c // (r * r), r, r, h, w).transpose(0, 3, 1, 4, 2).reshape(c // (r * r), h * r, w * r)
        else:
            raise ValueError(l.kind)
    return blobs[layers[-1].tops[0]]


def trim_x4(layers):
    """Drops the final downscale (the ×2 / ×3 files are the ×4 network and a resize)."""
    if layers[-1].kind == 'Interp' and layers[-1].params.get(0) == 3:
        last = layers.pop()
        layers[-1].tops = [last.tops[0]]
    return layers


# ---------------- Core ML ----------------

def build_coreml(layers, tile, out_path):
    import coremltools as ct
    from coremltools.converters.mil import Builder as mb
    from coremltools.converters.mil.mil import types

    @mb.program(input_specs=[mb.TensorSpec(shape=(1, 3, tile, tile), dtype=types.fp32)], opset_version=ct.target.iOS17)
    def prog(input):
        blobs = {}
        for l in layers:
            p = l.params
            if l.kind == 'Input':
                blobs[l.tops[0]] = input
            elif l.kind == 'Split':
                for t in l.tops:
                    blobs[t] = blobs[l.bottoms[0]]
            elif l.kind == 'Convolution':
                pad = p.get(4, 0)
                kwargs = dict(x=blobs[l.bottoms[0]], weight=l.weights['w'], pad_type='custom', pad=[pad, pad, pad, pad])
                if 'b' in l.weights:
                    kwargs['bias'] = l.weights['b']
                y = mb.conv(**kwargs)
                act = p.get(9, 0)
                if act == 1:
                    y = mb.relu(x=y)
                elif act == 2:
                    y = mb.leaky_relu(x=y, alpha=p[10][0])
                blobs[l.tops[0]] = y
            elif l.kind == 'PReLU':
                blobs[l.tops[0]] = mb.prelu(x=blobs[l.bottoms[0]], alpha=l.weights['slope'])
            elif l.kind == 'Concat':
                blobs[l.tops[0]] = mb.concat(values=[blobs[b] for b in l.bottoms], axis=1)
            elif l.kind == 'Eltwise':
                coeffs = p.get(1, [1.0] * len(l.bottoms))
                acc = None
                for c, b in zip(coeffs, l.bottoms):
                    term = blobs[b] if c == 1.0 else mb.mul(x=blobs[b], y=np.float32(c))
                    acc = term if acc is None else mb.add(x=acc, y=term)
                blobs[l.tops[0]] = acc
            elif l.kind == 'BinaryOp':
                blobs[l.tops[0]] = mb.add(x=blobs[l.bottoms[0]], y=blobs[l.bottoms[1]])
            elif l.kind == 'Interp':
                s = float(p[1])
                blobs[l.tops[0]] = mb.upsample_nearest_neighbor(x=blobs[l.bottoms[0]], scale_factor_height=s, scale_factor_width=s)
            elif l.kind == 'PixelShuffle':
                blobs[l.tops[0]] = mb.pixel_shuffle(x=blobs[l.bottoms[0]], upscale_factor=int(p[0]))
        return mb.clip(x=blobs[layers[-1].tops[0]], alpha=0.0, beta=1.0, name='output')

    model = ct.convert(prog, convert_to='mlprogram', compute_precision=ct.precision.FLOAT16,
                       minimum_deployment_target=ct.target.iOS17)
    model.short_description = 'Real-ESRGAN (BSD-3-Clause, Xintao Wang): x4, input and output RGB 0-1.'
    model.save(out_path)


if __name__ == '__main__':
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument('param')
    ap.add_argument('bin')
    ap.add_argument('--check', action='store_true')
    ap.add_argument('--coreml')
    ap.add_argument('--tile', type=int, default=128)
    a = ap.parse_args()
    layers = trim_x4(load(a.param, a.bin))
    print(len(layers), 'layers;', sum(1 for l in layers if l.kind == 'Convolution'), 'convolutions')
    if a.check:
        # A smooth test picture: the network should stay close to a plain upscale.
        y, x = np.mgrid[0:48, 0:48] / 47.0
        img = np.stack([0.5 + 0.4 * np.sin(6 * x), 0.5 + 0.4 * np.cos(5 * y), 0.3 + 0.5 * x * y]).astype(np.float32)
        out = run_numpy(layers, img)
        ref = img.repeat(4, 1).repeat(4, 2)
        mse = float(np.mean((np.clip(out, 0, 1) - ref) ** 2))
        print('output', out.shape, 'range', float(out.min()), float(out.max()), 'PSNR vs nearest', 10 * np.log10(1 / mse))
    if a.coreml:
        build_coreml(layers, a.tile, a.coreml)
        print('saved', a.coreml)

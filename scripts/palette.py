"""
Build the tri-tone ramps and check every pairing the UI actually uses.

The 600 stop is not a fixed step along the ramp. It is solved per hue as the
lightest shade that still carries AA body text on paper, which is what makes
"AA reserved for 600/700 on light backgrounds" true rather than aspirational.
Teal and sage sit at very different luminances, so an identical step would
leave one of them failing.
"""

AA = 4.5
MARGIN = 4.60  # a little headroom so rounding cannot drop a stop below AA


def hex_to_rgb(h):
    h = h.lstrip('#')
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def rgb_to_hex(rgb):
    return '#%02X%02X%02X' % tuple(max(0, min(255, round(c))) for c in rgb)


def mix(a, b, t):
    ra, rb = hex_to_rgb(a), hex_to_rgb(b)
    return rgb_to_hex(tuple(ra[i] + (rb[i] - ra[i]) * t for i in range(3)))


def luminance(h):
    def channel(c):
        c = c / 255.0
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = hex_to_rgb(h)
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)


def contrast(a, b):
    la, lb = luminance(a), luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)


ENDPOINTS = {
    'midnight': ('#16183A', '#2A2F6B'),
    'teal':     ('#0B7568', '#5DCAA5'),
    'sage':     ('#3E5648', '#BACBBC'),
}

paper = {
    0:   '#FFFFFF',
    50:  '#F8F9F8',
    100: '#F0F2F0',   # chrome only: panels and toolbars, not behind body text
    200: '#E3E7E4',
    300: '#CCD3CD',
    400: '#A8B2AA',
}
midnight_surface = {
    900: mix('#16183A', '#000000', 0.35),
    800: mix('#16183A', '#000000', 0.15),
}

# Body text sits on white or paper-50; paper-100 and below carry chrome only.
TEXT_SURFACES = [paper[0], paper[50]]


def solve_600(dark, light):
    """
    Lightest point on the ramp that still clears AA on every text surface.

    Returns 0.25 when the whole ramp already clears AA, as Midnight's does:
    there the threshold never binds, so an even step keeps the ramp usable
    for surfaces and fills instead of collapsing every stop onto one colour.
    """
    if min(contrast(light, bg) for bg in TEXT_SURFACES) >= MARGIN:
        return 0.25
    low, high = 0.0, 1.0
    for _ in range(40):
        mid = (low + high) / 2
        shade = mix(dark, light, mid)
        if min(contrast(shade, bg) for bg in TEXT_SURFACES) >= MARGIN:
            low = mid
        else:
            high = mid
    return low


ramps = {}
factors = {}
for name, (dark, light) in ENDPOINTS.items():
    f600 = solve_600(dark, light)
    factors[name] = f600
    # 500/400 spread evenly between the 600 stop and the light endpoint.
    ramps[name] = {
        700: dark,
        600: mix(dark, light, f600),
        500: mix(dark, light, f600 + (1 - f600) * 0.34),
        400: mix(dark, light, f600 + (1 - f600) * 0.67),
        300: light,
    }

print('=== ramps (700 darkest, 300 lightest) ===')
for name, ramp in ramps.items():
    print(f'{name:9s}', ' '.join(f'{k}:{v}' for k, v in sorted(ramp.items(), reverse=True)),
          f'  [600 solved at t={factors[name]:.3f}]')
print('paper    ', ' '.join(f'{k}:{v}' for k, v in sorted(paper.items())))
print('surfaces ', ' '.join(f'{k}:{v}' for k, v in sorted(midnight_surface.items(), reverse=True)))

failures = []

print('\n=== body text: 600 and 700 on text surfaces (must be >= 4.5) ===')
for bg_name, bg in (('white', paper[0]), ('paper-50', paper[50])):
    for name, ramp in ramps.items():
        for stop in (700, 600):
            r = contrast(ramp[stop], bg)
            ok = r >= AA
            print(f'  {name}-{stop} on {bg_name}: {r:.2f} {"OK" if ok else "FAIL"}')
            if not ok:
                failures.append(f'{name}-{stop} on {bg_name} = {r:.2f}')

print('\n=== 500/400/300 on white: decorative only, must not carry body text ===')
for name, ramp in ramps.items():
    for stop in (500, 400, 300):
        r = contrast(ramp[stop], paper[0])
        print(f'  {name}-{stop}: {r:.2f}' + ('  (still AA, but reserved for fills)' if r >= AA else ''))

print('\n=== white on filled buttons (600/700 must be >= 4.5) ===')
for name, ramp in ramps.items():
    for stop in (700, 600):
        r = contrast('#FFFFFF', ramp[stop])
        ok = r >= AA
        print(f'  white on {name}-{stop}: {r:.2f} {"OK" if ok else "FAIL"}')
        if not ok:
            failures.append(f'white on {name}-{stop} = {r:.2f}')

print('\n=== dark theme: text on midnight surfaces (must be >= 4.5) ===')
for bg_name, bg in (('midnight-900', midnight_surface[900]),
                    ('midnight-800', midnight_surface[800]),
                    ('midnight-700', ramps['midnight'][700])):
    for label, colour in (('paper-100', paper[100]), ('paper-200', paper[200]),
                          ('teal-300', ramps['teal'][300]), ('teal-400', ramps['teal'][400]),
                          ('sage-300', ramps['sage'][300]), ('paper-400', paper[400])):
        r = contrast(colour, bg)
        ok = r >= AA
        print(f'  {label} on {bg_name}: {r:.2f} {"OK" if ok else "below AA"}')
        if label != 'paper-400' and not ok:
            failures.append(f'{label} on {bg_name} = {r:.2f}')

print('\nFAILURES:', failures if failures else 'none')

print('\n=== CSS tokens ===')
for name, ramp in ramps.items():
    for stop in (700, 600, 500, 400, 300):
        print(f'    -{name}-{stop}: {ramp[stop]};')
for k, v in sorted(paper.items()):
    print(f'    -paper-{k}: {v};')
for k, v in sorted(midnight_surface.items(), reverse=True):
    print(f'    -surface-{k}: {v};')

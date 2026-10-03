"""
Синтез оригинальных звуков «Энума Элиш» для мода Меч Разрыва.
Ничего не взято из чужих записей: всё генерируется из шума, синусоид и фильтров
по описанию характера/тайминга, полученного анализом референса.
"""
import numpy as np
import scipy.signal as sg
import scipy.io.wavfile as wav
import subprocess, os, sys

SR = 44100
rng = np.random.default_rng(1207)
OUT = sys.argv[1] if len(sys.argv) > 1 else "out"
os.makedirs(OUT, exist_ok=True)


# ---------------------------------------------------------------- helpers
def t_axis(dur):
    return np.arange(int(dur * SR)) / SR


def noise(n):
    return rng.standard_normal(n)


def bp(x, lo, hi, order=4):
    sos = sg.butter(order, [lo, hi], btype="band", fs=SR, output="sos")
    return sg.sosfilt(sos, x)


def lp(x, f, order=4):
    return sg.sosfilt(sg.butter(order, f, btype="low", fs=SR, output="sos"), x)


def hp(x, f, order=4):
    return sg.sosfilt(sg.butter(order, f, btype="high", fs=SR, output="sos"), x)


def env_adsr(n, a, d, s, r, sustain_level=0.7):
    a, d, r = int(a * SR), int(d * SR), int(r * SR)
    s_n = max(0, n - a - d - r)
    e = np.concatenate([
        np.linspace(0, 1, a, endpoint=False) if a else [],
        np.linspace(1, sustain_level, d, endpoint=False) if d else [],
        np.full(s_n, sustain_level),
        np.linspace(sustain_level, 0, r) if r else [],
    ])
    return np.pad(e, (0, max(0, n - len(e))))[:n]


def exp_decay(n, tau):
    return np.exp(-np.arange(n) / SR / tau)


def sweep_sine(dur, f0, f1, curve=3.0):
    t = t_axis(dur)
    k = (t / dur) ** (1 / curve)
    f = f0 + (f1 - f0) * k
    return np.sin(2 * np.pi * np.cumsum(f) / SR)


def reverb(x, seconds=2.0, mix=0.3, bright=3500):
    n = int(seconds * SR)
    ir = lp(noise(n), bright, 2) * exp_decay(n, seconds / 5)
    ir /= np.sqrt((ir ** 2).sum())
    wet = sg.fftconvolve(x, ir)
    dry = np.pad(x, (0, len(wet) - len(x)))
    return dry * (1 - mix) + wet * mix * 3


def sat(x, drive=2.0):
    return np.tanh(x * drive) / np.tanh(drive)


def norm(x, peak_db=-1.0):
    return x / (np.max(np.abs(x)) + 1e-9) * 10 ** (peak_db / 20)


def fade(x, fin=0.005, fout=0.05):
    a, b = int(fin * SR), int(fout * SR)
    x = x.copy()
    if a: x[:a] *= np.linspace(0, 1, a)
    if b: x[-b:] *= np.linspace(1, 0, b)
    return x


def crackle(n, rate, lo=2500, hi=9000, grain=0.004, amp_jitter=True):
    """Электрический треск: редкие короткие зерна высокочастотного шума."""
    out = np.zeros(n)
    count = int(rate * n / SR)
    g = int(grain * SR)
    for _ in range(count):
        p = rng.integers(0, max(1, n - g * 8))
        L = g * rng.integers(1, 8)
        z = noise(L) * exp_decay(L, grain * rng.uniform(0.5, 2))
        out[p:p + L] += z * (rng.uniform(0.2, 1.0) if amp_jitter else 1)
    return bp(out, lo, hi, 2)


def save(name, x, keep_level=False):
    x = x if keep_level else norm(x)
    w = os.path.join(OUT, name + ".wav")
    wav.write(w, SR, (x * 32767).astype(np.int16))
    o = os.path.join(OUT, name + ".ogg")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", w, "-c:a", "libvorbis", "-q:a", "6", o], check=True)
    return x


# ---------------------------------------------------------------- 1. зарядка: ревущий алый вихрь (бесшовная петля 4 с)
def charge_loop():
    L = 4.0
    xf = 0.6  # кроссфейд для бесшовности
    N = int(L * SR)
    X = int(xf * SR)
    n = N + X + 16
    t = np.arange(n) / SR
    # глубокий гул 32 Гц с гармониками и биениями (LFO кратны 0.25 Гц → петля)
    drone = (np.sin(2 * np.pi * 32 * t) * 1.0 + np.sin(2 * np.pi * 64.5 * t) * 0.55
             + np.sin(2 * np.pi * 96 * t + 0.4 * np.sin(2 * np.pi * 0.5 * t)) * 0.3
             + np.sin(2 * np.pi * 43 * t) * 0.35)
    drone = sat(drone * 0.6, 1.8) * (0.8 + 0.2 * np.sin(2 * np.pi * 0.25 * t))
    # ветер: полосы шума, каждая «проносится» со своей фазой — ощущение вращения
    wind = np.zeros(n)
    bands = [(180, 420), (350, 800), (700, 1500), (1300, 2800), (2500, 5000)]
    for i, (lo, hi) in enumerate(bands):
        lfo = 0.5 + 0.5 * np.sin(2 * np.pi * (0.5 + 0.25 * (i % 3)) * t + i * 1.3)
        wind += bp(noise(n), lo, hi, 2) * lfo ** 2 * (1.0 - i * 0.12)
    # свист вихря: узкая полоса, частота гуляет
    whistle = np.zeros(n)
    fc = 1100 + 500 * np.sin(2 * np.pi * 0.75 * t)
    ph = np.cumsum(fc) / SR
    whistle = np.sin(2 * np.pi * ph) * bp(noise(n), 50, 400, 2) * 0.6
    sparks = crackle(n, 9, 2500, 9000) * 1.6
    x = drone * 0.9 + wind * 0.55 + whistle * 0.12 + sparks * 0.5
    x = lp(x, 9000, 2)
    # бесшовная склейка
    head = x[:X] * np.linspace(0, 1, X) + x[N:N + X] * np.linspace(1, 0, X)
    loop = np.concatenate([head, x[X:N]])
    return loop


# ---------------------------------------------------------------- 1b. зарядка v2: «космическая» раскрутка Эа
def loudness(x, drive=1.6, peak_db=-0.6):
    """Плотнее и громче: мягкая компрессия (tanh), затем пик на -0.6 дБ."""
    x = x / (np.max(np.abs(x)) + 1e-9)
    x = np.tanh(x * drive) / np.tanh(drive)
    return x / (np.max(np.abs(x)) + 1e-9) * 10 ** (peak_db / 20)


def charge_loop_cosmic():
    """
    Артефакт, существовавший до сотворения мира:
    - суббас 27.5 Гц (ощущается телом) с медленными биениями;
    - турбинный вой трёх вращающихся цилиндров: гармоники 110 Гц с «флаттером» вращения
      (в игре высота растёт с зарядом → раскрутка);
    - неземной хор-гул: кластеры чистых тонов в квинтах/октавах с расстройкой, длинная реверберация;
    - мерцание звёзд: редкие высокие колокольчики с долгим затуханием;
    - тихий вихрь пространства на фоне.
    """
    L = 4.0
    gen = 12.0                       # генерируем длиннее и берём середину — хвосты реверба тоже «закольцуются»
    n = int(gen * SR)
    t = np.arange(n) / SR
    # суббас
    sub = (np.sin(2 * np.pi * 27.5 * t) + 0.6 * np.sin(2 * np.pi * 55.25 * t) + 0.3 * np.sin(2 * np.pi * 82.5 * t))
    sub = sat(sub * 0.7, 2.0) * (0.85 + 0.15 * np.sin(2 * np.pi * 0.25 * t))
    # турбина: гармоники 110 Гц, флаттер 3 цилиндров (разные частоты: 6, 7.5, 9 Гц → верх/низ в одну сторону, середина в другую)
    turb = np.zeros(n)
    for h, a in [(1, 1.0), (2, 0.7), (3, 0.5), (4, 0.35), (6, 0.25), (8, 0.15), (12, 0.08)]:
        turb += np.sin(2 * np.pi * 110 * h * t + h * 0.7) * a
    flutter = (0.55 + 0.15 * np.sin(2 * np.pi * 6.0 * t) + 0.15 * np.sin(2 * np.pi * 7.5 * t + 1.0)
               + 0.15 * np.sin(2 * np.pi * 9.0 * t + 2.0))
    turb = lp(turb * flutter, 4000, 2)
    # хор-гул «космоса»: A-минорный кластер с расстройкой (биения 0.25–0.5 Гц)
    pad = np.zeros(n)
    for f, a in [(110, .8), (110.25, .8), (164.8, .55), (165.3, .5), (220, .6), (220.5, .5), (329.6, .35), (330.2, .3),
                 (440, .25), (659.3, .15), (880.6, .1)]:
        vib = 1 + 0.002 * np.sin(2 * np.pi * 0.5 * t + f)
        pad += np.sin(2 * np.pi * np.cumsum(f * vib) / SR) * a
    pad *= 0.7 + 0.3 * np.sin(2 * np.pi * 0.25 * t)
    # звёзды
    stars = np.zeros(n)
    for _ in range(int(gen * 3)):
        s0 = rng.integers(0, n - SR)
        f = rng.choice([1760, 1975.5, 2637, 2960, 3520, 3951, 5274])
        L2 = int(rng.uniform(0.6, 1.5) * SR)
        stars[s0:s0 + L2] += np.sin(2 * np.pi * f * np.arange(L2) / SR) * exp_decay(L2, rng.uniform(0.2, 0.6)) * rng.uniform(.2, .6)
    # вихрь пространства
    wind = np.zeros(n)
    for i, (lo, hi) in enumerate([(200, 500), (500, 1200), (1200, 3000)]):
        wind += bp(noise(n), lo, hi, 2) * (0.5 + 0.5 * np.sin(2 * np.pi * (0.5 + 0.25 * i) * t + i)) ** 2
    dry = sub * 1.0 + turb * 0.45 + pad * 0.35 + stars * 0.25 + wind * 0.25
    wet = reverb(dry, 3.5, 0.45, bright=6000)[:n]
    # середина + бесшовная склейка
    N = int(L * SR); X = int(0.5 * SR); s0 = int(4.0 * SR)
    seg = wet[s0:s0 + N + X]
    loop = np.concatenate([seg[:X] * np.linspace(0, 1, X) + seg[N:N + X] * np.linspace(1, 0, X), seg[X:N]])
    return loudness(loop, 1.8)


# ---------------------------------------------------------------- 2. треск пространства (3 варианта)
def crack(seed):
    global rng
    rng = np.random.default_rng(seed)
    dur = 0.9
    n = int(dur * SR)
    t = t_axis(dur)
    # хлёсткий разряд
    zap = crackle(n, 120, 1800, 10000, grain=0.002) * exp_decay(n, 0.12)
    # стеклянный излом: негармоничные призвуки
    glass = sum(np.sin(2 * np.pi * f * t + rng.uniform(0, 6)) * exp_decay(n, rng.uniform(0.05, 0.25)) * a
                for f, a in [(2150 * rng.uniform(.9, 1.1), .5), (3420, .35), (5230 * rng.uniform(.9, 1.1), .25), (7100, .15)])
    # глухой удар-трещина в «ткани»
    thud = sweep_sine(dur, 140, 45, 2) * exp_decay(n, 0.09)
    crunch = lp(noise(n), 1200, 2) * exp_decay(n, 0.05)
    x = zap * 0.9 + glass * 0.35 + thud * 0.9 + crunch * 0.6
    return fade(reverb(x, 0.9, 0.25)[: int(1.4 * SR)], 0.001, 0.2)


# ---------------------------------------------------------------- 3. полный заряд: суббас-удар + белая вспышка + «засасывание»
def full_charge():
    dur = 4.0
    n = int(dur * SR)
    t = t_axis(dur)
    boom = sat(sweep_sine(dur, 75, 26, 2.5) * exp_decay(n, 1.1), 2.5)
    flash = hp(noise(n), 2500, 2) * exp_decay(n, 0.18)
    shimmer = sum(np.sin(2 * np.pi * f * t) * exp_decay(n, 0.9) * (0.5 + 0.5 * np.sin(2 * np.pi * 6 * t + f))
                  for f in (2960, 3950, 4440, 5920)) * 0.15
    # затем высокие частоты «уходят», остаётся гул (как перед криком)
    tail = lp(noise(n), 300, 2) * np.clip((t - 0.3) / 0.7, 0, 1) * exp_decay(n, 1.4) * 0.8
    x = boom * 1.0 + flash * 0.35 + shimmer + tail
    return fade(reverb(x, 2.5, 0.25)[: int(5 * SR)], 0.002, 0.8)


# ---------------------------------------------------------------- 4. выстрел: взрыв + ревущий поток со звенящими тонами
def release():
    dur = 4.5
    n = int(dur * SR)
    t = t_axis(dur)
    crackhit = (noise(n) * exp_decay(n, 0.035)) + hp(noise(n), 3000, 2) * exp_decay(n, 0.12) * 0.6
    sub = sat(sweep_sine(dur, 90, 32, 2) * exp_decay(n, 1.4), 2.2)
    roar_env = env_adsr(n, 0.04, 0.3, 2.6, 1.5, 0.8)
    roar = (bp(noise(n), 150, 900, 2) * 0.9 + bp(noise(n), 900, 3200, 2) * 0.5) * roar_env
    # «поток» с нарастающей высотой: фильтр медленно раскрывается
    rise = np.zeros(n)
    for k in range(6):
        seg = slice(int(k * n / 6), int((k + 1) * n / 6))
        rise[seg] = bp(noise(n), 400 + k * 250, 1200 + k * 600, 2)[seg]
    rise *= roar_env * 0.35
    # звенящие тоны ~650 Гц (металлический резонанс разорванного пространства)
    ring = np.zeros(n)
    for f, a in [(652, 1.0), (668, 0.8), (1304, 0.45), (1960, 0.3), (2610, 0.18)]:
        ring += np.sin(2 * np.pi * f * t + rng.uniform(0, 6)) * a
    ring *= env_adsr(n, 0.12, 0.4, 2.2, 1.6, 0.6) * 0.18
    x = crackhit * 0.8 + sub * 0.9 + roar * 0.7 + rise + ring
    return fade(reverb(x, 3.0, 0.3)[: int(6 * SR)], 0.001, 1.0)


# ---------------------------------------------------------------- 5. удар: серия ударов, огромный бум, обломки
def impact():
    dur = 6.0
    n = int(dur * SR)
    t = t_axis(dur)
    x = np.zeros(n)
    for start, amp in [(0.0, 1.0), (0.22, 0.55), (0.5, 0.4)]:
        s = int(start * SR)
        m = n - s
        hit = sat(sweep_sine(m / SR, 110, 28, 2.2) * exp_decay(m, 1.6 if start == 0 else 0.5), 3.0)
        blast = lp(noise(m), 2500, 2) * exp_decay(m, 0.5 if start == 0 else 0.2)
        click = hp(noise(m), 4000, 2) * exp_decay(m, 0.02)
        x[s:] += (hit * 1.0 + blast * 0.7 + click * 0.5) * amp
    rumble = lp(noise(n), 120, 2) * env_adsr(n, 0.1, 0.5, 3.0, 2.0, 0.7) * 1.6
    debris = crackle(n, 70, 1200, 7000, grain=0.006) * np.clip((t - 0.4) / 0.5, 0, 1) * exp_decay(n, 1.6) * 1.2
    tone = sum(np.sin(2 * np.pi * f * t) for f in (588, 594)) * np.clip((t - 1.5) / 1.0, 0, 1) * exp_decay(n, 1.8) * 0.06
    x = x + rumble + debris + tone
    return fade(reverb(x, 3.5, 0.3)[: int(7.5 * SR)], 0.001, 1.5)


if __name__ == "__main__":
    out = {}
    out["charge_loop"] = save("charge_loop", charge_loop_cosmic(), keep_level=True)
    for i, seed in enumerate((11, 22, 33), 1):
        out[f"crack{i}"] = save(f"crack{i}", crack(seed))
    rng = np.random.default_rng(99)
    out["full_charge"] = save("full_charge", full_charge())
    out["release"] = save("release", release())
    out["impact"] = save("impact", impact())

    # Демо: как это прозвучит в игре (15 с зарядки → выстрел → удар)
    def at(buf, x, sec, gain=1.0):
        s = int(sec * SR)
        e = min(len(buf), s + len(x))
        buf[s:e] += x[: e - s] * gain

    demo = np.zeros(int(26 * SR))
    loop = out["charge_loop"]
    charge_len = 15.0
    reps = int(np.ceil(charge_len * SR / len(loop))) + 1
    long_loop = np.tile(loop, reps)[: int(charge_len * SR)]
    # громкость и высота плавно растут с зарядом (как в игре: pitch 0.6 → 1.3)
    out_len = int(charge_len * SR)
    c = np.arange(out_len) / out_len
    rate = 0.6 + 0.7 * c
    pos = np.cumsum(rate)
    src = np.tile(loop, int(pos[-1] / len(loop)) + 2)
    pl = np.interp(pos, np.arange(len(src)), src) * (0.25 + 0.75 * c)
    at(demo, pl, 0.0, 0.8)
    for k in range(1, 10):
        at(demo, out[f"crack{(k % 3) + 1}"], k * 1.5, 0.35 + 0.05 * k)
    at(demo, out["full_charge"], 15.0, 0.9)
    at(demo, out["release"], 18.0, 1.0)
    at(demo, out["impact"], 18.6, 1.0)
    save("demo_enuma_elish", demo)
    print("ok")

"""
Звуки Эа по описанию саунд-дизайна «Вавилонии» (оригинальный синтез, без чужих записей):
- turbine_loop: механическая раскрутка — тяжёлая авиационная турбина / промышленный ротор,
  чистый металлический «отполированный» вой высокого давления (в игре высота растёт с зарядом);
- subhum_loop: сверхнизкий вибрирующий гул (закладывает уши), громкость растёт с зарядом;
- arc1..3: сухие щелчки красно-чёрных молний — рвущийся силовой кабель + хруст ломающегося льда;
- release: Bass Drop + Sonic Boom (тяжёлый, плотный, «тягучий»), затем растягивается и рвётся
  ткань реальности, переходя в гул засасываемого вакуума.
"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
import synth_enuma as S  # noqa: E402  (общие фильтры/реверб/сохранение)

SR = S.SR
OUT = sys.argv[1] if len(sys.argv) > 1 else "out"
S.OUT = OUT
os.makedirs(OUT, exist_ok=True)
rng = np.random.default_rng(4242)
S.rng = rng

import scipy.signal as sg  # noqa: E402
import scipy.io.wavfile as wav  # noqa: E402
import subprocess  # noqa: E402


def resonators(x, freqs, q=40.0):
    out = np.zeros_like(x)
    for f, a in freqs:
        b, a2 = sg.iirpeak(f, q, fs=SR)
        out += sg.lfilter(b, a2, x) * a
    return out


def loudness(x, drive=1.6, peak_db=-0.6):
    x = x / (np.max(np.abs(x)) + 1e-9)
    x = np.tanh(x * drive) / np.tanh(drive)
    return x / (np.max(np.abs(x)) + 1e-9) * 10 ** (peak_db / 20)


def make_loop(gen_fn, L=4.0, gen=10.0, xf=0.5, rev=None):
    n = int(gen * SR)
    x = gen_fn(n, np.arange(n) / SR)
    if rev:
        x = S.reverb(x, *rev)[:n]
    N, X, s0 = int(L * SR), int(xf * SR), int(3.0 * SR)
    seg = x[s0:s0 + N + X]
    return np.concatenate([seg[:X] * np.linspace(0, 1, X) + seg[N:N + X] * np.linspace(1, 0, X), seg[X:N]])


def save(name, x, normalize=True):
    if normalize:
        x = S.norm(x)
    w = os.path.join(OUT, name + ".wav")
    wav.write(w, SR, (np.clip(x, -1, 1) * 32767).astype(np.int16))
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", w, "-c:a", "libvorbis", "-q:a", "6", os.path.join(OUT, name + ".ogg")], check=True)
    return x


def turbine():
    def g(n, t):
        # вал ротора 55 Гц и плотный ряд гармоник — «металл»
        shaft = sum(np.sin(2 * np.pi * 55 * h * t + h) * a for h, a in
                    [(1, 1.0), (2, .8), (3, .55), (4, .45), (5, .3), (6, .25), (8, .18), (10, .12)])
        # вой лопаток: высокая частота прохождения лопаток + её гармоника, лёгкое биение
        blade = (np.sin(2 * np.pi * 2310 * t) + 0.6 * np.sin(2 * np.pi * 2318 * t) + 0.35 * np.sin(2 * np.pi * 4620 * t)
                 + 0.2 * np.sin(2 * np.pi * 6935 * t))
        blade *= 0.75 + 0.25 * np.sin(2 * np.pi * 0.5 * t)
        # компрессорный свист: узкая полоса шума вокруг 3.4 кГц
        whistle = S.bp(S.noise(n), 3200, 3700, 4) * 2.5
        # поток воздуха высокого давления
        rush = S.bp(S.noise(n), 400, 5000, 2) * (0.7 + 0.3 * np.sin(2 * np.pi * 0.25 * t))
        # «отполированный скрежет»: металл о металл, резонансы
        scrape = resonators(S.bp(S.noise(n), 600, 3000, 2), [(1180, .6), (1745, .5), (2620, .4)], q=60)
        x = S.sat(shaft * 0.25, 1.6) * 0.9 + blade * 0.22 + whistle * 0.35 + rush * 0.3 + scrape * 0.5
        return S.lp(x, 12000, 2)
    return loudness(make_loop(g, rev=(1.2, 0.2, 9000)), 1.6)


def subhum():
    def g(n, t):
        hum = (np.sin(2 * np.pi * 29 * t) + 0.7 * np.sin(2 * np.pi * 31 * t)   # биение 2 Гц — «вибрирующий»
               + 0.45 * np.sin(2 * np.pi * 58.5 * t) + 0.2 * np.sin(2 * np.pi * 87 * t))
        hum = S.sat(hum * 0.6, 2.2)
        rumble = S.lp(S.noise(n), 60, 4) * 3
        return hum + rumble * 0.6
    return loudness(make_loop(g), 1.5)


def arc(seed):
    r = np.random.default_rng(seed)
    S.rng = r
    dur = 0.8
    n = int(dur * SR)
    t = S.t_axis(dur)
    # разрыв толстого кабеля: очень сухой щелчок + короткий жужжащий разряд 50·k Гц
    snap = S.noise(n) * S.exp_decay(n, 0.004) * 2.0
    buzz_f = r.choice([100, 150, 200])
    buzz = np.sign(np.sin(2 * np.pi * buzz_f * t)) * S.exp_decay(n, 0.05) * 0.25
    zap = S.crackle(n, 400, 2000, 12000, grain=0.0008) * S.exp_decay(n, 0.06) * 1.5
    # хруст льда: несколько хрупких негармоничных «тиков»
    ice = np.zeros(n)
    for _ in range(r.integers(4, 9)):
        p0 = int(r.uniform(0.0, 0.18) * SR)
        L = int(0.06 * SR)
        f = r.uniform(2500, 7500)
        ice[p0:p0 + L] += np.sin(2 * np.pi * f * np.arange(L) / SR) * S.exp_decay(L, r.uniform(0.004, 0.02)) * r.uniform(.3, .9)
    thump = np.sin(2 * np.pi * np.cumsum(np.linspace(120, 50, n)) / SR) * S.exp_decay(n, 0.03) * 0.8
    x = snap + buzz + zap + ice * 0.7 + thump
    return S.fade(S.reverb(x, 0.6, 0.15)[: int(1.1 * SR)], 0.0005, 0.25)


def release():
    dur = 7.0
    n = int(dur * SR)
    t = S.t_axis(dur)
    # Bass Drop: глубокий импульс, падение 70 → 22 Гц, долгий
    drop = S.sat(S.sweep_sine(dur, 70, 22, 1.6) * S.exp_decay(n, 1.6) * 1.4, 2.5)
    # Sonic Boom: N-волна (резкий фронт, минус-фаза), «плотный и тягучий»
    nwave = np.zeros(n)
    L = int(0.12 * SR)
    nwave[:L] = np.linspace(1, -1, L)
    nwave = S.lp(nwave, 900, 2) * 1.6
    blast = S.lp(S.noise(n), 1800, 2) * S.exp_decay(n, 0.45) * 1.2
    crack = S.hp(S.noise(n), 3000, 2) * S.exp_decay(n, 0.03)
    # растягиваемая ткань реальности: скрип с падающей высотой + рвущиеся волокна
    stretch = np.zeros(n)
    s0, s1 = int(0.5 * SR), int(2.6 * SR)
    m = s1 - s0
    f = np.linspace(900, 140, m)
    creak = np.sin(2 * np.pi * np.cumsum(f) / SR) * (0.5 + 0.5 * np.abs(S.bp(S.noise(m), 8, 40, 2)) * 6)
    stretch[s0:s1] = S.bp(creak, 100, 3000, 2) * np.hanning(m) * 0.7
    tear = np.zeros(n)
    for k in range(900):
        u = rng.random()
        p0 = s0 + int(u ** 0.7 * m)
        Lg = rng.integers(30, 260)
        if p0 + Lg >= n: continue
        tear[p0:p0 + Lg] += S.noise(Lg) * np.hanning(Lg) * (0.2 + 0.8 * u)
    tear = S.bp(tear, 500, 8000, 2) * 0.9
    # засасываемый вакуум: нарастающий низкий поток + гул, который втягивается в точку
    suck_env = np.clip((t - 2.0) / 1.8, 0, 1) ** 2 * np.clip((6.8 - t) / 1.2, 0, 1)
    suck = S.bp(S.noise(n), 60, 700, 2) * suck_env * 1.4
    vac_hum = (np.sin(2 * np.pi * np.cumsum(np.linspace(80, 40, n)) / SR) * suck_env) * 0.6
    x = drop + nwave + blast + crack * 0.6 + stretch + tear + suck + vac_hum
    return loudness(S.fade(S.reverb(x, 4.5, 0.3, bright=4500)[: int(8 * SR)], 0.0005, 1.0), 1.5)


if __name__ == "__main__":
    save("charge_loop", turbine(), normalize=False)
    save("subhum_loop", subhum(), normalize=False)
    for i, seed in enumerate((101, 202, 303), 1):
        save(f"crack{i}", arc(seed))
    save("release", release(), normalize=False)
    print("ok")

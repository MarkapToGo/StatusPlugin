#!/usr/bin/env python3
"""StatusPlugin benchmark: a local Paper server with protocol-level bots.

  python3 benchmark/bench.py compare <old.jar> <new.jar> [options]
  python3 benchmark/bench.py run <label> <plugin.jar | none> [options]
  python3 benchmark/bench.py summary <label> [<label> ...]
  python3 benchmark/bench.py profile <label>

See benchmark/README.md for what is measured and how to read the results.
"""
import argparse, collections, hashlib, json, os, random, shutil, socket, struct, subprocess, sys, time
import urllib.request, uuid, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
RESOURCES = os.path.join(REPO, 'src', 'main', 'resources')

PAPER_VERSION = '26.2'
VIA_VERSION = '5.12.0'
LIBERTYBANS_VERSION = '1.1.4'
SERVER_PORT, RCON_PORT, CONTROL_PORT = 25565, 25575, 8099
RCON_PASSWORD = 'bench'
STATUSES = ['ADMIN', 'MOD', 'VIP', 'MEMBER', 'AFK', 'CAM', 'STREAMING', 'RECORDING', 'BUILDING',
            'MINING', 'FARMING', 'EXPLORING', 'FIGHTING', 'TRADING', 'CODING']


# ---------------------------------------------------------------------------- setup

class Env:
    def __init__(self, args):
        self.work = os.path.abspath(args.work_dir)
        self.cache = os.path.join(self.work, 'cache')
        self.server = os.path.join(self.work, 'server')
        self.results = os.path.join(self.work, 'results')
        self.java = find_java()
        self.javac = os.path.join(os.path.dirname(self.java), 'javac')
        for d in (self.cache, self.server, self.results):
            os.makedirs(d, exist_ok=True)


def find_java():
    candidates = [os.environ.get('STATUSBENCH_JAVA')]
    if os.environ.get('JAVA_HOME'):
        candidates.append(os.path.join(os.environ['JAVA_HOME'], 'bin', 'java'))
    if sys.platform == 'darwin':
        r = subprocess.run(['/usr/libexec/java_home', '-v', '25+'], capture_output=True, text=True)
        if r.returncode == 0:
            candidates.append(os.path.join(r.stdout.strip(), 'bin', 'java'))
    candidates.append(shutil.which('java'))
    for c in candidates:
        if c and os.path.exists(c):
            out = subprocess.run([c, '-version'], capture_output=True, text=True).stderr
            try:
                major = int(out.split('"')[1].split('.')[0])
            except (IndexError, ValueError):
                continue
            if major >= 25:
                return c
    sys.exit('Java 25+ not found (set STATUSBENCH_JAVA or JAVA_HOME)')


def fetch(url):
    # PaperMC's API asks clients to identify themselves
    req = urllib.request.Request(url, headers={'User-Agent': 'StatusPlugin-benchmark/1.0'})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def download(url, target):
    if not os.path.exists(target):
        print('downloading', url, flush=True)
        tmp = target + '.part'
        with open(tmp, 'wb') as f:
            f.write(fetch(url))
        os.replace(tmp, target)
    return target


def paper_jar(env, build):
    api = 'https://fill.papermc.io/v3/projects/paper/versions/%s/builds' % PAPER_VERSION
    builds = json.loads(fetch(api))
    if build:
        builds = [b for b in builds if str(b['id']) == str(build)]
    else:
        builds = [b for b in builds if b.get('channel') == 'STABLE'] or builds
    if not builds:
        sys.exit('Paper build not found')
    b = builds[0]
    dl = b['downloads']['server:default']
    target = os.path.join(env.cache, dl['name'])
    download(dl['url'], target)
    sha = hashlib.sha256(open(target, 'rb').read()).hexdigest()
    if sha != dl['checksums']['sha256']:
        os.remove(target)
        sys.exit('Paper checksum mismatch')
    return target, b['id']


def setup(env, args):
    """Download server/plugins, patch the server, build the harness and install the bots"""
    paper, build = paper_jar(env, args.paper_build)
    via = download('https://github.com/ViaVersion/ViaVersion/releases/download/%s/ViaVersion-%s.jar'
                   % (VIA_VERSION, VIA_VERSION), os.path.join(env.cache, 'ViaVersion-%s.jar' % VIA_VERSION))
    viab = download('https://github.com/ViaVersion/ViaBackwards/releases/download/%s/ViaBackwards-%s.jar'
                    % (VIA_VERSION, VIA_VERSION), os.path.join(env.cache, 'ViaBackwards-%s.jar' % VIA_VERSION))
    libertybans = None
    if args.libertybans:
        libertybans = download('https://github.com/A248/LibertyBans/releases/download/%s/LibertyBans_Release-%s.jar'
                               % (LIBERTYBANS_VERSION, LIBERTYBANS_VERSION),
                               os.path.join(env.cache, 'LibertyBans-%s.jar' % LIBERTYBANS_VERSION))

    eula = os.path.join(env.server, 'eula.txt')
    if args.accept_eula:
        open(eula, 'w').write('eula=true\n')
    if 'eula=true' not in (open(eula).read() if os.path.exists(eula) else ''):
        sys.exit('Running the test server requires accepting the Minecraft EULA '
                 '(https://aka.ms/MinecraftEULA). Re-run with --accept-eula.')

    write_server_config(env)
    patched = os.path.join(env.server, 'versions', PAPER_VERSION, 'paper-%s.jar' % PAPER_VERSION)
    if not os.path.exists(patched) or os.path.getmtime(patched) < os.path.getmtime(paper):
        print('patching server', flush=True)
        subprocess.run([env.java, '-Dpaperclip.patchonly=true', '-jar', paper], cwd=env.server, check=True,
                       stdout=subprocess.DEVNULL)
    build_harness(env)

    if not os.path.isdir(os.path.join(HERE, 'bots', 'node_modules')):
        subprocess.run(['npm', 'install', '--no-audit', '--no-fund'], cwd=os.path.join(HERE, 'bots'), check=True)
    return {'paper': paper, 'paperBuild': build, 'via': [via, viab], 'libertybans': libertybans}


def write_server_config(env):
    open(os.path.join(env.server, 'server.properties'), 'w').write('\n'.join([
        'online-mode=false', 'enforce-secure-profile=false', 'level-type=minecraft\\:flat',
        'generate-structures=false', 'view-distance=4', 'simulation-distance=4', 'max-players=200',
        'difficulty=peaceful', 'spawn-protection=0', 'enable-rcon=true', 'rcon.password=' + RCON_PASSWORD,
        'rcon.port=%d' % RCON_PORT, 'server-port=%d' % SERVER_PORT, 'motd=StatusPlugin benchmark',
        'sync-chunk-writes=false', '']))
    # All bots connect from 127.0.0.1, so the per-IP join throttle has to go
    bukkit = os.path.join(env.server, 'bukkit.yml')
    open(bukkit, 'w').write('settings:\n  connection-throttle: -1\n')


def build_harness(env):
    src = os.path.join(HERE, 'harness', 'src', 'de', 'stylelabor', 'benchmark', 'StatusBench.java')
    jar = os.path.join(env.cache, 'StatusBench.jar')
    inputs = [src, os.path.join(HERE, 'harness', 'plugin.yml')]
    if os.path.exists(jar) and all(os.path.getmtime(jar) > os.path.getmtime(i) for i in inputs):
        return jar
    libs = []
    for root, _, files in os.walk(os.path.join(env.server, 'libraries')):
        libs += [os.path.join(root, f) for f in files if f.endswith('.jar')]
    out = os.path.join(env.cache, 'harness-classes')
    shutil.rmtree(out, ignore_errors=True)
    subprocess.run([env.javac, '-nowarn', '-d', out, '-cp', os.pathsep.join(libs), src], check=True)
    with zipfile.ZipFile(jar, 'w') as z:
        z.write(inputs[1], 'plugin.yml')
        for root, _, files in os.walk(out):
            for f in files:
                p = os.path.join(root, f)
                z.write(p, os.path.relpath(p, out))
    return jar


# ---------------------------------------------------------------------------- plugin data

def offline_uuid(name):
    h = bytearray(hashlib.md5(('OfflinePlayer:' + name).encode()).digest())
    h[6] = (h[6] & 0x0f) | 0x30
    h[8] = (h[8] & 0x3f) | 0x80
    return str(uuid.UUID(bytes=bytes(h)))


def replace_once(text, old, new):
    assert text.count(old) == 1, 'pattern not found exactly once: ' + old[:60]
    return text.replace(old, new)


def prepare_plugins(env, args, jar, deps):
    pdir = os.path.join(env.server, 'plugins')
    shutil.rmtree(pdir, ignore_errors=True)
    os.makedirs(os.path.join(pdir, 'bStats'))
    # Never report the test server to the real bStats page
    open(os.path.join(pdir, 'bStats', 'config.yml'), 'w').write(
        'enabled: false\nserverUuid: 00000000-0000-0000-0000-000000000000\nlogFailedRequests: false\n')
    for j in deps['via']:
        shutil.copy(j, pdir)
    shutil.copy(os.path.join(env.cache, 'StatusBench.jar'), pdir)
    if deps['libertybans']:
        shutil.copy(deps['libertybans'], os.path.join(pdir, 'LibertyBans.jar'))
    if jar == 'none':
        return

    shutil.copy(jar, os.path.join(pdir, 'StatusPlugin.jar'))
    sp = os.path.join(pdir, 'StatusPlugin')
    os.makedirs(sp)
    for r in ('status-options.yml', 'tablist.yml', 'language.yml'):
        shutil.copy(os.path.join(RESOURCES, r), sp)
    cfg = open(os.path.join(RESOURCES, 'config.yml')).read()
    # Only change to the default config: no update check from the test server
    cfg = replace_once(cfg, 'entirely)\n  enabled: true', 'entirely)\n  enabled: false')

    if args.stress:
        # Exercise nametags, legacy color codes and a per-player rotating message
        cfg = replace_once(cfg, 'May conflict with other plugins using teams.\n  enabled: false',
                           'May conflict with other plugins using teams.\n  enabled: true')
        cfg = replace_once(cfg, 'player-format: "<status> <gray><player></gray> <deaths_formatted> <dark_gray><countrycode></dark_gray>"',
                           'player-format: "&7[<deaths>] <status> <player> &8<countrycode> &#FF00FFx"')
        cfg = replace_once(cfg, 'format: "<status> <gray><player></gray> <dark_gray>»</dark_gray> <white><message></white>"',
                           'format: "&8[<deaths>] <status> <player> &7» &f<message>"')
        tl = open(os.path.join(sp, 'tablist.yml')).read()
        tl = replace_once(tl, '    - "<rotating>"\n', '    - "<rotating>"\n    - "&6Legacy &#FF00FF hex <overworld>/<nether>/<end> &lbold"\n')
        tl = replace_once(tl, '    - "<gray>Players: <white><online></white>/<white><max></white></gray>"\n',
                          '    - "<gray>Players: <white><online></white>/<white><max></white></gray>"\n'
                          '    - "<gray>You: <status> <deaths_formatted> &a<player>"\n    - "<gray>Total deaths <total_deaths>"\n')
        tl = replace_once(tl, '    - "<aqua>Join our Discord!</aqua>"', '    - "&b<player> &7died &c<deaths>&7x <rotating> online: <online>"')
        open(os.path.join(sp, 'tablist.yml'), 'w').write(tl)
        so = open(os.path.join(sp, 'status-options.yml')).read()
        so = replace_once(so, '\nstatuses:\n', '\nstatuses:\n  LEGACY: "&c[&lLEG&r&c] &#00FF00x"\n')
        open(os.path.join(sp, 'status-options.yml'), 'w').write(so)
    open(os.path.join(sp, 'config.yml'), 'w').write(cfg)

    # Deterministic statuses / deaths for the bots (20% without status)
    rnd = random.Random(42)
    with open(os.path.join(sp, 'player-status.yml'), 'w') as fs, open(os.path.join(sp, 'player-deaths.yml'), 'w') as fd:
        for i in range(1, args.bots + 1):
            u = offline_uuid('Bot%03d' % i)
            if args.stress and i % 7 == 0:
                fs.write('%s: LEGACY\n' % u)
            elif i % 5 != 0:
                fs.write('%s: %s\n' % (u, STATUSES[i % len(STATUSES)]))
            fd.write('%s: %d\n' % (u, rnd.randint(0, 60)))
        # A mature server remembers every player who ever joined
        for _ in range(args.big_data):
            fs.write('%s: %s\n' % (uuid.UUID(int=rnd.getrandbits(128), version=4), rnd.choice(STATUSES)))
    if args.big_data:
        with open(os.path.join(sp, 'status-requests.yml'), 'w') as fr:
            n = args.big_data // 20
            fr.write('meta:\n  next-id: %d\nrequests:\n' % (n + 1))
            for i in range(1, n + 1):
                fr.write('  REQ-%d:\n    uuid: %s\n    player: Old%d\n    raw: "[Old %d]"\n    format: "[Old %d]<reset>"\n'
                         '    timestamp: 1700000000000\n    status: DENIED\n    reason: old\n    notified: true\n'
                         % (i, uuid.UUID(int=rnd.getrandbits(128), version=4), i, i, i))


# ---------------------------------------------------------------------------- server control

def port_free(port):
    with socket.socket() as s:
        return s.connect_ex(('127.0.0.1', port)) != 0


def rcon(cmd):
    s = socket.create_connection(('127.0.0.1', RCON_PORT), timeout=120)

    def send(i, t, p):
        d = struct.pack('<ii', i, t) + p.encode() + b'\0\0'
        s.sendall(struct.pack('<i', len(d)) + d)

    def recv():
        hdr = b''
        while len(hdr) < 4:
            hdr += s.recv(4 - len(hdr))
        n = struct.unpack('<i', hdr)[0]
        d = b''
        while len(d) < n:
            d += s.recv(n - len(d))
        return d[8:-2].decode(errors='replace')

    send(1, 3, RCON_PASSWORD)
    recv()
    send(2, 2, cmd)
    r = recv()
    s.close()
    return r


def bots(path):
    with urllib.request.urlopen('http://127.0.0.1:%d%s' % (CONTROL_PORT, path), timeout=600) as r:
        return json.loads(r.read())


def wait_bots(proc, timeout=60):
    """Wait until the bot process answers on its control port"""
    t0 = time.time()
    while time.time() - t0 < timeout:
        if proc.poll() is not None:
            sys.exit('bot process exited, see bots.log')
        try:
            return bots('/status')
        except OSError:
            time.sleep(0.5)
    sys.exit('bot process did not start')


def wait_log(logpath, needle, timeout=600, start_at=0):
    t0 = time.time()
    while time.time() - t0 < timeout:
        with open(logpath, errors='replace') as f:
            f.seek(start_at)
            for line in f:
                if needle in line:
                    return line
        time.sleep(1)
    raise TimeoutError(needle)


def harness(logpath, cmd, done):
    """Run a harness command that finishes asynchronously and wait for its result line"""
    pos = os.path.getsize(logpath)
    rcon(cmd)
    return wait_log(logpath, 'BENCH-DONE ' + done, 600, pos).strip()


# ---------------------------------------------------------------------------- one run

def run(env, args, label, jar):
    out = os.path.join(env.results, label)
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)

    def log(*a):
        msg = time.strftime('%H:%M:%S ') + ' '.join(str(x) for x in a)
        print('[%s] %s' % (label, msg), flush=True)
        with open(os.path.join(out, 'run.log'), 'a') as f:
            f.write(msg + '\n')

    for port in (SERVER_PORT, RCON_PORT, CONTROL_PORT):
        if not port_free(port):
            sys.exit('port %d is in use (another server or benchmark running?)' % port)
    deps = setup(env, args)
    prepare_plugins(env, args, jar, deps)
    shutil.rmtree(os.path.join(env.server, 'world', 'players'), ignore_errors=True)
    json.dump({'label': label, 'jar': jar, 'paperBuild': deps['paperBuild'], 'bots': args.bots,
               'stress': args.stress, 'bigData': args.big_data, 'libertybans': bool(deps['libertybans'])},
              open(os.path.join(out, 'meta.json'), 'w'), indent=1)

    logpath = os.path.join(out, 'server.log')
    java = [env.java, '-Xms4G', '-Xmx4G', '-XX:+UseG1GC', '-jar', deps['paper'], '--nogui']
    srv = subprocess.Popen(java, cwd=env.server, stdin=subprocess.PIPE, stdout=open(logpath, 'w'),
                           stderr=subprocess.STDOUT)
    botproc = None
    sp_dir = os.path.join(env.server, 'plugins', 'StatusPlugin')
    try:
        wait_log(logpath, 'Done (', 300)
        log('server up, pid', srv.pid)
        time.sleep(15)

        if not args.dump_only:
            rcon('bench rec start'); time.sleep(30); log('idle', rcon('bench rec stop idle-0-players'))

        # Join storm: all bots, one every 50 ms
        rcon('bench rec start')
        botproc = subprocess.Popen(['node', os.path.join(HERE, 'bots', 'bots.js'), str(args.bots), '50',
                                    str(SERVER_PORT), str(CONTROL_PORT)], cwd=os.path.join(HERE, 'bots'),
                                   stdout=open(os.path.join(out, 'bots.log'), 'w'), stderr=subprocess.STDOUT)
        wait_bots(botproc)
        t0 = time.time()
        while bots('/status')['joined'] < args.bots and time.time() - t0 < 120:
            time.sleep(0.5)
        time.sleep(5)
        log('join storm', rcon('bench rec stop join-storm'))
        st = bots('/status')
        json.dump(st, open(os.path.join(out, 'bots-join.json'), 'w'), indent=1)
        if st['joined'] < args.bots:
            log('WARNING only', st['joined'], 'bots joined', st['kicks'])

        if jar != 'none' and args.dump_only:
            time.sleep(8)
            log(rcon('bench dump dump'))
            log(rcon('bench scenario scenario'))
            for f in ('dump.txt', 'scenario.txt'):
                shutil.copy(os.path.join(env.server, 'plugins', 'StatusBench', f), os.path.join(out, f))
            # Background saving: the scenario's status changes must be on disk within a few seconds
            time.sleep(7)
            shutil.copy(os.path.join(sp_dir, 'player-status.yml'), os.path.join(out, 'player-status-after-7s.yml'))
        elif not args.dump_only:
            time.sleep(20)
            bots('/reset'); rcon('bench rec start')
            time.sleep(120)
            log('steady', rcon('bench rec stop steady'))
            stats = bots('/stats'); log('packets', json.dumps(stats))
            json.dump(stats, open(os.path.join(out, 'bots-steady.json'), 'w'), indent=1)

            if jar != 'none':
                log(rcon('bench dump dump'))
                shutil.copy(os.path.join(env.server, 'plugins', 'StatusBench', 'dump.txt'), os.path.join(out, 'dump.txt'))
                time.sleep(10)
                if deps['libertybans']:
                    libertybans_check(out, log)
                chat = bots('/chat?n=200&rate=10'); log('chat', json.dumps(chat))
                json.dump(chat, open(os.path.join(out, 'bots-chat.json'), 'w'), indent=1)
                time.sleep(5)
                log(harness(logpath, 'bench tab 100 tab', 'tab'))
                time.sleep(3)
                log(harness(logpath, 'bench join 30 join-event', 'join'))
                time.sleep(3)
                log(harness(logpath, 'bench chat 300 chat-render', 'chat'))
                if args.big_data:
                    time.sleep(3)
                    log(harness(logpath, 'bench setstatus 60 set-status', 'set-status'))
                    time.sleep(3)
                    log(harness(logpath, 'bench request 60 submit-request', 'submit-request'))
                if args.jfr:
                    profile_run(env, srv.pid, out, logpath, log)

        try:
            bots('/quit')
        except Exception:
            pass
        time.sleep(3)
    finally:
        if botproc:
            botproc.kill()
        try:
            rcon('stop')
        except Exception as e:
            log('stop failed', e)
        try:
            srv.wait(90)
        except Exception:
            srv.kill()
        res = os.path.join(env.server, 'plugins', 'StatusBench', 'results.jsonl')
        if os.path.exists(res):
            shutil.copy(res, os.path.join(out, 'results.jsonl'))
        if os.path.exists(os.path.join(sp_dir, 'player-status.yml')):
            shutil.copy(os.path.join(sp_dir, 'player-status.yml'), os.path.join(out, 'player-status-after-stop.yml'))
        log('finished')


def libertybans_check(out, log):
    """Mute one bot: its message must not reach others and it must be told it is muted"""
    rcon('libertybans mute Bot010 1h benchmark mute')
    time.sleep(3)
    for b in ('Bot010', 'Bot011'):
        bots('/inbox?bot=' + b)
    bots('/say?bot=Bot010&msg=muted-check-4711')
    time.sleep(3)
    muted_inbox = bots('/inbox?bot=Bot010')
    other_inbox = bots('/inbox?bot=Bot011')
    result = {
        'mutedSenderGotMuteNotice': any('You are muted' in m or 'muted' in m.lower() for m in muted_inbox),
        'messageReachedOthers': any('muted-check-4711' in m for m in other_inbox),
        'mutedSenderInbox': muted_inbox,
    }
    log('libertybans', json.dumps({k: v for k, v in result.items() if k != 'mutedSenderInbox'}))
    json.dump(result, open(os.path.join(out, 'libertybans.json'), 'w'), indent=1)
    rcon('libertybans unmute Bot010')
    time.sleep(3)


def profile_run(env, pid, out, logpath, log):
    jcmd = os.path.join(os.path.dirname(env.java), 'jcmd')
    jfrtool = os.path.join(os.path.dirname(env.java), 'jfr')
    jfc = os.path.join(env.cache, 'bench.jfc')
    if not os.path.exists(jfc):
        subprocess.run([jfrtool, 'configure', 'method-profiling=max', '--output', jfc], check=True,
                       stdout=subprocess.DEVNULL)
    subprocess.run([jcmd, str(pid), 'JFR.start', 'name=bench', 'settings=' + jfc], check=True, stdout=subprocess.DEVNULL)
    harness(logpath, 'bench tab 60 tab-profiled', 'tab')
    harness(logpath, 'bench chat 200 chat-profiled', 'chat')
    subprocess.run([jcmd, str(pid), 'JFR.stop', 'name=bench', 'filename=' + os.path.join(out, 'profile.jfr')],
                   check=True, stdout=subprocess.DEVNULL)
    log('profile written')


# ---------------------------------------------------------------------------- reporting

def load(env, label):
    d = {'label': label}
    base = os.path.join(env.results, label)
    p = os.path.join(base, 'results.jsonl')
    if os.path.exists(p):
        for line in open(p):
            r = json.loads(line)
            d[r['label']] = r
    for name in ('bots-join', 'bots-steady', 'bots-chat', 'libertybans'):
        p = os.path.join(base, name + '.json')
        if os.path.exists(p):
            d[name] = json.load(open(p))
    return d


ROWS = [
    ('Join storm: main-thread ms total', ('join-storm', 'sumMs'), '{:.0f}'),
    ('Join storm: worst tick ms', ('join-storm', 'max'), '{:.0f}'),
    ('Join storm: bot login p50 ms', ('bots-join', 'joinMs', 'p50'), '{:.0f}'),
    ('Steady: mean tick ms', ('steady', 'mean'), '{:.2f}'),
    ('Steady: refresh-tick mean ms', ('steady', 'spikePhaseMeanMs'), '{:.1f}'),
    ('Steady: other ticks mean ms', ('steady', 'otherTicksMeanMs'), '{:.2f}'),
    ('Steady: ticks > 50 ms (120 s)', ('steady', 'ticksOver50ms'), '{}'),
    ('player_info pkts / bot / s', ('bots-steady', 'perBotPerSec', 'player_info'), '{:.2f}'),
    ('teams pkts / bot / s', ('bots-steady', 'perBotPerSec', 'teams'), '{:.2f}'),
    ('header pkts / bot / s', ('bots-steady', 'perBotPerSec', 'playerlist_header'), '{:.2f}'),
    ('Tab refresh mean ms', ('tab', 'mean'), '{:.2f}'),
    ('Tab refresh p99 ms', ('tab', 'p99'), '{:.2f}'),
    ('Join event incl. next tick ms', ('join-event', 'extraTickMsJoinPlusNext', 'mean'), '{:.2f}'),
    ('Chat render ms / message', ('chat-render', 'mean'), '{:.3f}'),
    ('Chat delivery p50 ms', ('bots-chat', 'allReceiptsMs', 'p50'), '{:.1f}'),
    ('Chat delivery p99 ms', ('bots-chat', 'allReceiptsMs', 'p99'), '{:.1f}'),
    ('setStatus mean ms', ('set-status', 'mean'), '{:.3f}'),
    ('setStatus max ms', ('set-status', 'max'), '{:.3f}'),
    ('submitRequest mean ms', ('submit-request', 'mean'), '{:.3f}'),
    ('LibertyBans: muted msg blocked', ('libertybans', 'messageReachedOthers'), '{}'),
    ('LibertyBans: mute notice shown', ('libertybans', 'mutedSenderGotMuteNotice'), '{}'),
]


def summary(env, labels):
    data = [load(env, l) for l in labels]

    def get(d, path, fmt):
        cur = d
        for k in path:
            if not isinstance(cur, dict) or k not in cur:
                return '-'
            cur = cur[k]
        if path[-1] == 'messageReachedOthers':
            return 'yes' if cur is False else 'NO'
        if isinstance(cur, bool):
            return 'yes' if cur else 'NO'
        return fmt.format(cur)

    rows = [(label, [get(d, path, fmt) for d in data]) for label, path, fmt in ROWS]
    rows = [r for r in rows if any(v != '-' for v in r[1])]
    w = max(len(r[0]) for r in rows)
    print(' ' * w + ' | ' + ' | '.join('%14s' % l[-14:] for l in labels))
    for label, vals in rows:
        print('%s | %s' % (label.ljust(w), ' | '.join('%14s' % v for v in vals)))


def diff_files(env, a, b, name):
    fa, fb = (os.path.join(env.results, l, name) for l in (a, b))
    if not (os.path.exists(fa) and os.path.exists(fb)):
        return '%s: missing' % name
    if open(fa).read() == open(fb).read():
        return '%s: IDENTICAL (%d lines)' % (name, len(open(fa).read().splitlines()))
    r = subprocess.run(['diff', fa, fb], capture_output=True, text=True)
    return '%s: DIFFERENT\n%s' % (name, r.stdout[:3000])


def profile(env, label):
    jfr = os.path.join(env.results, label, 'profile.jfr')
    jfrtool = os.path.join(os.path.dirname(env.java), 'jfr')
    raw = subprocess.run([jfrtool, 'print', '--json', '--stack-depth', '96', '--events', 'jdk.ExecutionSample', jfr],
                         capture_output=True, text=True, check=True).stdout
    total, plugin, leaf, entry = (collections.Counter() for _ in range(4))
    for e in json.loads(raw)['recording']['events']:
        v = e['values']
        name = v['sampledThread'].get('javaName') or '?'
        group = ('main' if name == 'Server thread' else 'chat' if name.startswith('Async Chat')
                 else 'netty' if 'netty' in name.lower() else 'other')
        total[group] += 1
        frames = [f['method']['type']['name'].replace('/', '.') + '.' + f['method']['name']
                  for f in (v.get('stackTrace') or {}).get('frames', [])]
        ours = [f for f in frames if 'de.stylelabor.statusplugin' in f]
        if ours:
            plugin[group] += 1
            leaf[frames[0]] += 1
            entry[ours[-1]] += 1
    print('samples per thread (all / inside StatusPlugin):')
    for g, n in total.most_common():
        print('  %-8s %7d / %7d' % (g, n, plugin[g]))
    print('entry points into the plugin:')
    for k, n in entry.most_common(8):
        print('  %7d  %s' % (n, k))
    print('hottest frames under plugin code:')
    for k, n in leaf.most_common(12):
        print('  %7d  %s' % (n, k))


# ---------------------------------------------------------------------------- CLI

def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--work-dir', default=os.path.join(HERE, 'work'),
                   help='server, downloads and results (default: benchmark/work)')
    sub = p.add_subparsers(dest='cmd', required=True)

    def run_options(sp):
        sp.add_argument('--bots', type=int, default=100)
        sp.add_argument('--big-data', type=int, default=0, metavar='N',
                        help='pre-fill player-status.yml with N players (and N/20 old requests) and time saves')
        sp.add_argument('--libertybans', action='store_true', help='install LibertyBans and check a real mute')
        sp.add_argument('--jfr', action='store_true', help='record a CPU profile of the tab refresh and chat')
        sp.add_argument('--paper-build', help='Paper %s build (default: latest stable)' % PAPER_VERSION)
        sp.add_argument('--accept-eula', action='store_true', help='accept the Minecraft EULA for the test server')

    r = sub.add_parser('run', help='benchmark one plugin jar (or "none" for a control run)')
    r.add_argument('label')
    r.add_argument('jar')
    r.add_argument('--stress', action='store_true', help='config with nametags, legacy codes, per-player lines')
    r.add_argument('--dump-only', action='store_true', help='only record rendered output (correctness check)')
    run_options(r)

    c = sub.add_parser('compare', help='correctness check + benchmark of two jars and a control run')
    c.add_argument('old')
    c.add_argument('new')
    c.add_argument('--no-control', action='store_true')
    run_options(c)

    s = sub.add_parser('summary', help='print results of earlier runs side by side')
    s.add_argument('labels', nargs='+')

    pr = sub.add_parser('profile', help='summarise the CPU profile of a run made with --jfr')
    pr.add_argument('label')

    args = p.parse_args()
    env = Env(args)
    if args.cmd == 'run':
        jar = args.jar if args.jar == 'none' else os.path.abspath(args.jar)
        run(env, args, args.label, jar)
        summary(env, [args.label])
    elif args.cmd == 'compare':
        old, new = os.path.abspath(args.old), os.path.abspath(args.new)
        args.dump_only = True
        for stress in (False, True):
            args.stress = stress
            suffix = '-stress' if stress else ''
            run(env, args, 'old-output' + suffix, old)
            run(env, args, 'new-output' + suffix, new)
        args.dump_only, args.stress = False, False
        labels = ['old', 'new'] + ([] if args.no_control else ['control'])
        for label, jar in zip(labels, (old, new, 'none')):
            run(env, args, label, jar)
        print()
        for suffix in ('', '-stress'):
            for name in ('dump.txt', 'scenario.txt'):
                print('output%s %s' % (suffix, diff_files(env, 'old-output' + suffix, 'new-output' + suffix, name)))
        print()
        summary(env, labels)
    elif args.cmd == 'summary':
        summary(env, args.labels)
    elif args.cmd == 'profile':
        profile(env, args.label)


if __name__ == '__main__':
    main()

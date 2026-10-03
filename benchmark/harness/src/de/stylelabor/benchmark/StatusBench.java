package de.stylelabor.benchmark;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Benchmark harness for StatusPlugin, driven by bench.py over RCON.
 * <p>
 * It only uses public Bukkit/Paper APIs and reflection, so the same build works
 * against every StatusPlugin version and on a server without StatusPlugin
 * (control run). Results are appended to plugins/StatusBench/results.jsonl.
 */
public final class StatusBench extends JavaPlugin implements Listener {

    private static final int MAX_TICKS = 200_000;
    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();

    private final int[] tickNumbers = new int[MAX_TICKS];
    private final double[] tickDurations = new double[MAX_TICKS];
    private volatile boolean recording = false;
    private int recorded = 0;

    private File resultsFile;

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        resultsFile = new File(getDataFolder(), "results.jsonl");
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent event) {
        if (recording && recorded < MAX_TICKS) {
            tickNumbers[recorded] = event.getTickNumber();
            tickDurations[recorded] = event.getTickDuration();
            recorded++;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("bench info | rec start | rec stop <label> | tab <n> <label> | join <n> <label>"
                    + " | chat <n> <label> | setstatus <n> <label> | request <n> <label> | dump <file> | scenario <file>");
            return true;
        }
        try {
            switch (args[0]) {
                case "info" -> sender.sendMessage("online=" + Bukkit.getOnlinePlayers().size()
                        + " statusplugin=" + (statusPlugin() != null));
                case "rec" -> handleRec(sender, args);
                case "tab" -> benchTab(sender, Integer.parseInt(args[1]), args[2]);
                case "join" -> benchJoin(sender, Integer.parseInt(args[1]), args[2]);
                case "chat" -> benchChat(sender, Integer.parseInt(args[1]), args[2]);
                case "setstatus" -> benchSetStatus(sender, Integer.parseInt(args[1]), args[2]);
                case "request" -> benchRequest(sender, Integer.parseInt(args[1]), args[2]);
                case "dump" -> dump(sender, args[1]);
                case "scenario" -> scenario(sender, args[1]);
                default -> sender.sendMessage("unknown subcommand");
            }
        } catch (Exception e) {
            getLogger().severe("bench failed: " + e);
            e.printStackTrace();
            sender.sendMessage("error: " + e);
        }
        return true;
    }

    // ---------------------------------------------------------------- tick recording

    private void handleRec(CommandSender sender, String[] args) {
        if (args[1].equals("start")) {
            recorded = 0;
            recording = true;
            sender.sendMessage("recording");
            return;
        }
        recording = false;
        double[] d = Arrays.copyOf(tickDurations, recorded);
        int[] t = Arrays.copyOf(tickNumbers, recorded);
        Map<String, Object> out = stats(d);
        out.put("label", args[2]);
        out.put("kind", "ticks");
        out.put("online", Bukkit.getOnlinePlayers().size());
        int over50 = 0;
        double sum = 0;
        for (double v : d) {
            if (v > 50.0) over50++;
            sum += v;
        }
        out.put("ticksOver50ms", over50);
        out.put("sumMs", sum);

        // The tab refresh runs every 100 ticks: find the phase (tick % 100) with the
        // highest mean and compare it with all other ticks.
        if (recorded >= 300) {
            double[] phaseSum = new double[100];
            int[] phaseCnt = new int[100];
            for (int i = 0; i < recorded; i++) {
                int p = Math.floorMod(t[i], 100);
                phaseSum[p] += d[i];
                phaseCnt[p]++;
            }
            int best = 0;
            for (int p = 1; p < 100; p++) {
                if (phaseSum[p] / Math.max(1, phaseCnt[p]) > phaseSum[best] / Math.max(1, phaseCnt[best])) best = p;
            }
            List<Double> spike = new ArrayList<>();
            List<Double> rest = new ArrayList<>();
            for (int i = 0; i < recorded; i++) {
                (Math.floorMod(t[i], 100) == best ? spike : rest).add(d[i]);
            }
            out.put("spikePhaseMeanMs", spike.stream().mapToDouble(x -> x).average().orElse(0));
            out.put("spikePhaseCount", spike.size());
            out.put("otherTicksMeanMs", rest.stream().mapToDouble(x -> x).average().orElse(0));
        }
        write(out);
        sender.sendMessage(toJson(out));
    }

    // ---------------------------------------------------------------- tab refresh

    private void benchTab(CommandSender sender, int iterations, String label) throws Exception {
        Object tab = manager("getTabListManager");
        if (tab == null) { sender.sendMessage("no StatusPlugin"); return; }
        Method updateAll = tab.getClass().getMethod("updateAllPlayers");
        AtomicInteger rotating = rotatingIndex(tab);

        int warmup = 10;
        double[] samples = new double[iterations];
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                try {
                    // Advance the rotating message like the real task does between refreshes
                    rotating.incrementAndGet();
                    long start = System.nanoTime();
                    updateAll.invoke(tab);
                    if (i >= warmup) samples[i - warmup] = (System.nanoTime() - start) / 1e6;
                } catch (Exception e) {
                    getLogger().severe("tab bench: " + e);
                    cancel();
                    return;
                }
                if (++i >= iterations + warmup) {
                    cancel();
                    finish(samples, label, "tabRefresh");
                }
            }
        }.runTaskTimer(this, 1L, 2L);
        sender.sendMessage("tab bench started");
    }

    // ---------------------------------------------------------------- join event

    private void benchJoin(CommandSender sender, int n, String label) {
        Plugin sp = statusPlugin();
        if (sp == null) { sender.sendMessage("no StatusPlugin"); return; }
        List<RegisteredListener> listeners = listeners(PlayerJoinEvent.getHandlerList().getRegisteredListeners(), sp);
        double[] direct = new double[n];
        List<Integer> joinTicks = new ArrayList<>();
        // Record ticks so work deferred to the next tick is measured too
        recorded = 0;
        recording = true;
        new BukkitRunnable() {
            int i = 0;
            int tickCounter = 0;

            @Override
            public void run() {
                // One fake join every 10 ticks
                if (++tickCounter % 10 != 0) return;
                Player p = randomPlayer();
                PlayerJoinEvent ev = new PlayerJoinEvent(p, Component.text(p.getName() + " joined the game"));
                long start = System.nanoTime();
                for (RegisteredListener rl : listeners) {
                    try { rl.callEvent(ev); } catch (Exception e) { getLogger().severe("join: " + e); }
                }
                direct[i] = (System.nanoTime() - start) / 1e6;
                joinTicks.add(Bukkit.getCurrentTick());
                if (++i < n) return;
                cancel();
                Bukkit.getScheduler().runTaskLater(StatusBench.this, () -> {
                    recording = false;
                    Map<Integer, Double> byTick = new HashMap<>();
                    for (int k = 0; k < recorded; k++) byTick.put(tickNumbers[k], tickDurations[k]);
                    // Idle baseline: ticks 3..8 after each join
                    List<Double> idle = new ArrayList<>();
                    double[] withJoin = new double[joinTicks.size()];
                    for (int j = 0; j < joinTicks.size(); j++) {
                        int jt = joinTicks.get(j);
                        withJoin[j] = byTick.getOrDefault(jt, 0.0) + byTick.getOrDefault(jt + 1, 0.0);
                        for (int o = 3; o <= 8; o++) if (byTick.containsKey(jt + o)) idle.add(byTick.get(jt + o));
                    }
                    double idleMean = idle.stream().mapToDouble(x -> x).average().orElse(0);
                    double[] extra = new double[withJoin.length];
                    for (int j = 0; j < withJoin.length; j++) extra[j] = withJoin[j] - 2 * idleMean;
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("label", label);
                    out.put("kind", "joinEvent");
                    out.put("online", Bukkit.getOnlinePlayers().size());
                    out.put("listenerDirect", stats(direct));
                    out.put("extraTickMsJoinPlusNext", stats(extra));
                    out.put("idleTickMeanMs", idleMean);
                    write(out);
                    getLogger().info("BENCH-DONE join " + toJson(out));
                }, 10L);
            }
        }.runTaskTimer(this, 1L, 1L);
        sender.sendMessage("join bench started");
    }

    // ---------------------------------------------------------------- chat render

    private void benchChat(CommandSender sender, int n, String label) {
        Plugin sp = statusPlugin();
        if (sp == null) { sender.sendMessage("no StatusPlugin"); return; }
        List<RegisteredListener> listeners = listeners(AsyncChatEvent.getHandlerList().getRegisteredListeners(), sp);
        String[] texts = {
                "hello everyone, how is it going?",
                "check this out https://example.com/some/page?x=1 it's cool",
                "gg",
                "anyone want to trade diamonds for emeralds at spawn",
        };
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            int warmup = 20;
            double[] samples = new double[n];
            int renders = 0;
            for (int i = 0; i < n + warmup; i++) {
                List<? extends Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
                Player p = online.get(ThreadLocalRandom.current().nextInt(online.size()));
                Set<Audience> viewers = new LinkedHashSet<>(online);
                viewers.add(Bukkit.getConsoleSender());
                Component msg = Component.text(texts[i % texts.length]);
                long start = System.nanoTime();
                AsyncChatEvent ev = new AsyncChatEvent(true, p, viewers, ChatRenderer.defaultRenderer(), msg, msg, null);
                for (RegisteredListener rl : listeners) {
                    try { rl.callEvent(ev); } catch (Exception e) { getLogger().severe("chat: " + e); }
                }
                ChatRenderer renderer = ev.renderer();
                Component displayName = p.displayName();
                // Same call pattern as Paper's ChatProcessor: render once per viewer
                for (Audience viewer : viewers) {
                    if (renderer.render(p, displayName, msg, viewer) == null) throw new IllegalStateException();
                }
                if (i >= warmup) {
                    samples[i - warmup] = (System.nanoTime() - start) / 1e6;
                    renders += viewers.size();
                }
            }
            Map<String, Object> out = stats(samples);
            out.put("label", label);
            out.put("kind", "chatMessage");
            out.put("online", Bukkit.getOnlinePlayers().size());
            out.put("viewerCallsPerMessage", renders / Math.max(1, n));
            write(out);
            getLogger().info("BENCH-DONE chat " + toJson(out));
        });
        sender.sendMessage("chat bench started");
    }

    // ---------------------------------------------------------------- status / request saves

    /**
     * Time StatusManager.setStatus on the main thread (what /status does before
     * updating the tab list), one call per tick
     */
    private void benchSetStatus(CommandSender sender, int n, String label) throws Exception {
        Object statusManager = manager("getStatusManager");
        if (statusManager == null) { sender.sendMessage("no StatusPlugin"); return; }
        Method setStatus = statusManager.getClass().getMethod("setStatus", UUID.class, String.class);
        String[] statuses = {"VIP", "MEMBER"};
        double[] samples = new double[n];
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                Player p = randomPlayer();
                try {
                    long start = System.nanoTime();
                    setStatus.invoke(statusManager, p.getUniqueId(), statuses[i % 2]);
                    samples[i] = (System.nanoTime() - start) / 1e6;
                } catch (Exception e) {
                    getLogger().severe("setstatus: " + e);
                }
                if (++i >= n) {
                    cancel();
                    finish(samples, label, "setStatus");
                }
            }
        }.runTaskTimer(this, 1L, 1L);
        sender.sendMessage("setstatus bench started");
    }

    /**
     * Time RequestManager.submitRequest on the main thread (what /status-suggest
     * does), one call per tick
     */
    private void benchRequest(CommandSender sender, int n, String label) throws Exception {
        Object requestManager = manager("getRequestManager");
        if (requestManager == null) { sender.sendMessage("no StatusPlugin"); return; }
        Method submit = requestManager.getClass().getMethod("submitRequest", Player.class, String.class);
        double[] samples = new double[n];
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                Player p = randomPlayer();
                try {
                    long start = System.nanoTime();
                    submit.invoke(requestManager, p, "[<gold>Bench " + i + "</gold>]");
                    samples[i] = (System.nanoTime() - start) / 1e6;
                } catch (Exception e) {
                    getLogger().severe("request: " + e);
                }
                if (++i >= n) {
                    cancel();
                    finish(samples, label, "submitRequest");
                }
            }
        }.runTaskTimer(this, 1L, 1L);
        sender.sendMessage("request bench started");
    }

    // ---------------------------------------------------------------- rendered output (correctness)

    /**
     * Write every player's list name, header, footer, team and a rendered chat
     * message as JSON, after a refresh with a fixed rotating message, so the
     * output of two plugin versions can be diffed.
     */
    private void dump(CommandSender sender, String name) throws Exception {
        Plugin sp = statusPlugin();
        if (sp == null) { sender.sendMessage("no StatusPlugin"); return; }
        Object tab = manager("getTabListManager");
        // Rotating message #4 contains no time/TPS values, so the output is deterministic
        rotatingIndex(tab).set(4);
        tab.getClass().getMethod("updateAllPlayers").invoke(tab);

        List<RegisteredListener> chatListeners = listeners(AsyncChatEvent.getHandlerList().getRegisteredListeners(), sp);
        List<Player> players = sortedPlayers();
        StringBuilder sb = new StringBuilder();
        for (Player p : players) {
            sb.append("== ").append(p.getName()).append('\n');
            sb.append("list   ").append(GSON.serialize(p.playerListName())).append('\n');
            sb.append("header ").append(GSON.serialize(p.playerListHeader())).append('\n');
            sb.append("footer ").append(GSON.serialize(p.playerListFooter())).append('\n');
            sb.append("team   ").append(describeTeam(p)).append('\n');
            Component msg = Component.text("hi all, see https://example.com/x?y=1 now");
            Set<Audience> viewers = new LinkedHashSet<>(players);
            AsyncChatEvent ev = new AsyncChatEvent(false, p, viewers, ChatRenderer.defaultRenderer(), msg, msg, null);
            for (RegisteredListener rl : chatListeners) rl.callEvent(ev);
            sb.append("chat   ").append(GSON.serialize(ev.renderer().render(p, p.displayName(), msg, players.get(0))))
                    .append('\n');
        }
        writeFile(name, sb.toString());
        sender.sendMessage("dumped " + players.size());
    }

    /**
     * Apply the changes a running server sees (status/death changes, other
     * plugins overwriting list names or teams) and record the tab list state after
     * each step, so two plugin versions can be diffed.
     */
    private void scenario(CommandSender sender, String name) throws Exception {
        Object tab = manager("getTabListManager");
        if (tab == null) { sender.sendMessage("no StatusPlugin"); return; }
        Object status = manager("getStatusManager");
        Object deaths = manager("getDeathTracker");
        Object nametag = manager("getNametagManager");
        Method updatePlayer = tab.getClass().getMethod("updatePlayer", Player.class);
        Method updateAll = tab.getClass().getMethod("updateAllPlayers");
        Method nametagUpdate = nametag.getClass().getMethod("updatePlayer", Player.class);
        Method setStatus = status.getClass().getMethod("setStatus", UUID.class, String.class);
        Method clearStatus = status.getClass().getMethod("clearStatus", UUID.class);
        Method setDeaths = deaths.getClass().getMethod("setDeaths", UUID.class, int.class);

        List<Player> players = sortedPlayers();
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        StringBuilder sb = new StringBuilder();

        // 1. /status VIP
        Player p1 = players.get(0);
        setStatus.invoke(status, p1.getUniqueId(), "VIP");
        updatePlayer.invoke(tab, p1);
        nametagUpdate.invoke(nametag, p1);
        record(sb, "status set", p1);

        // 2. Another plugin replaces the list name; the next refresh restores it
        Player p2 = players.get(1);
        p2.playerListName(Component.text("overwritten"));
        updateAll.invoke(tab);
        record(sb, "list name overwritten + refresh", p2);

        // 3. Death count changes
        Player p3 = players.get(2);
        setDeaths.invoke(deaths, p3.getUniqueId(), 99);
        updatePlayer.invoke(tab, p3);
        record(sb, "deaths set", p3);

        // 4. /status-clear
        Player p4 = players.get(3);
        clearStatus.invoke(status, p4.getUniqueId());
        updatePlayer.invoke(tab, p4);
        nametagUpdate.invoke(nametag, p4);
        record(sb, "status cleared", p4);

        // 5. Another plugin moves the player to its own team; the next refresh moves them back
        Player p5 = players.get(4);
        Team foreign = scoreboard.getTeam("zz_bench_foreign");
        if (foreign == null) foreign = scoreboard.registerNewTeam("zz_bench_foreign");
        foreign.addEntry(p5.getName());
        updateAll.invoke(tab);
        record(sb, "moved to foreign team + refresh", p5);
        if (foreign.getSize() == 0) foreign.unregister();

        // 6. Another plugin changes the team prefix; the next refresh restores it
        Player p6 = players.get(5);
        Team team6 = scoreboard.getEntryTeam(p6.getName());
        if (team6 != null) team6.prefix(Component.text("X "));
        updateAll.invoke(tab);
        record(sb, "team prefix overwritten + refresh", p6);

        // 7. Status set again and a refresh with nothing changed
        setStatus.invoke(status, p4.getUniqueId(), "MOD");
        updatePlayer.invoke(tab, p4);
        nametagUpdate.invoke(nametag, p4);
        updateAll.invoke(tab);
        updateAll.invoke(tab);
        for (Player p : players.subList(0, 6)) record(sb, "after two more refreshes", p);

        writeFile(name, sb.toString());
        sender.sendMessage("scenario done " + p1.getName() + "=VIP " + p4.getName() + "=MOD");
    }

    private void record(StringBuilder sb, String step, Player p) {
        sb.append("## ").append(step).append(" -> ").append(p.getName()).append('\n');
        sb.append("list   ").append(GSON.serialize(p.playerListName())).append('\n');
        sb.append("team   ").append(describeTeam(p)).append('\n');
    }

    private String describeTeam(Player p) {
        Team team = Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(p.getName());
        return team == null ? "-" : team.getName() + " " + GSON.serialize(team.prefix());
    }

    // ---------------------------------------------------------------- helpers

    private Plugin statusPlugin() {
        Plugin p = Bukkit.getPluginManager().getPlugin("StatusPlugin");
        return p != null && p.isEnabled() ? p : null;
    }

    private Object manager(String getter) throws Exception {
        Plugin sp = statusPlugin();
        return sp == null ? null : sp.getClass().getMethod(getter).invoke(sp);
    }

    private static AtomicInteger rotatingIndex(Object tab) throws Exception {
        Field field = tab.getClass().getDeclaredField("rotatingIndex");
        field.setAccessible(true);
        return (AtomicInteger) field.get(tab);
    }

    private static List<RegisteredListener> listeners(RegisteredListener[] all, Plugin plugin) {
        List<RegisteredListener> result = new ArrayList<>();
        for (RegisteredListener rl : all) {
            if (rl.getPlugin() == plugin) result.add(rl);
        }
        return result;
    }

    private static Player randomPlayer() {
        List<? extends Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        return online.get(ThreadLocalRandom.current().nextInt(online.size()));
    }

    private static List<Player> sortedPlayers() {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        players.sort(Comparator.comparing(Player::getName));
        return players;
    }

    private void finish(double[] samples, String label, String kind) {
        Map<String, Object> out = stats(samples);
        out.put("label", label);
        out.put("kind", kind);
        out.put("online", Bukkit.getOnlinePlayers().size());
        write(out);
        getLogger().info("BENCH-DONE " + label + " " + toJson(out));
    }

    private static Map<String, Object> stats(double[] values) {
        double[] s = values.clone();
        Arrays.sort(s);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("n", s.length);
        if (s.length == 0) return out;
        double sum = 0;
        for (double v : s) sum += v;
        out.put("mean", sum / s.length);
        out.put("p50", pct(s, 0.50));
        out.put("p90", pct(s, 0.90));
        out.put("p99", pct(s, 0.99));
        out.put("max", s[s.length - 1]);
        out.put("min", s[0]);
        return out;
    }

    private static double pct(double[] sorted, double p) {
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }

    private void writeFile(String name, String content) throws IOException {
        try (FileWriter w = new FileWriter(new File(getDataFolder(), name + ".txt"))) {
            w.write(content);
        }
    }

    private synchronized void write(Map<String, Object> out) {
        try (FileWriter w = new FileWriter(resultsFile, true)) {
            w.write(toJson(out) + "\n");
        } catch (IOException e) {
            getLogger().severe("write: " + e);
        }
    }

    private static String toJson(Object o) {
        if (o instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(e.getKey()).append("\":").append(toJson(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (o instanceof String s) return '"' + s.replace("\"", "\\\"") + '"';
        if (o instanceof Double d) return String.format(Locale.ROOT, "%.4f", d);
        return String.valueOf(o);
    }
}

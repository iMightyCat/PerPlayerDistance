package MightyDevelopment;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.event.EventBus;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.group.Group;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

public final class PerPlayerDistance extends JavaPlugin implements TabCompleter, CommandExecutor, Listener {

    private final MiniMessage mm = MiniMessage.miniMessage();
    private FileConfiguration messages;
    private Connection db;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadMessages();
        initDatabase();

        Objects.requireNonNull(getCommand("ppd")).setExecutor(this);
        Objects.requireNonNull(getCommand("ppd")).setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PpdExpansion(this).register();
        }

        LuckPerms lp = getLuckPerms();
        if (lp != null) {
            EventBus bus = lp.getEventBus();
            bus.subscribe(this, UserDataRecalculateEvent.class, event -> {
                Player p = Bukkit.getPlayer(event.getUser().getUniqueId());
                if (p != null && p.isOnline()) {
                    Bukkit.getScheduler().runTask(this, () -> applyBestDistance(p));
                }
            });
        }
    }

    @Override
    public void onDisable() {
        try { if (db != null && !db.isClosed()) db.close(); } catch (SQLException ignored) {}
    }

    private void loadMessages() {
        File f = new File(getDataFolder(), "messages.yml");
        if (f.exists()) {
            try {
                String content = new String(java.nio.file.Files.readAllBytes(f.toPath()));
                if (content.contains("{player}") || content.contains("{min}") || content.contains("{group}")) {
                    f.delete();
                }
            } catch (Exception ignored) {}
        }
        if (!f.exists()) saveResource("messages.yml", false);
        messages = YamlConfiguration.loadConfiguration(f);
    }

    private void send(CommandSender s, String path, TagResolver... r) {
        String prefix = messages.getString("prefix", "<gray>[PPD]</gray> ");
        String raw = messages.getString(path, "<red>Missing message: " + path);
        raw = raw.replace("<prefix>", prefix);
        s.sendMessage(mm.deserialize(raw, r));
    }

    private void initDatabase() {
        try {
            Driver h2driver = (Driver) Class.forName("MightyDevelopment.libs.h2.Driver")
                    .getDeclaredConstructor().newInstance();
            DriverManager.registerDriver(h2driver);
            String url = "jdbc:h2:file:" + getDataFolder().getAbsolutePath() + "/data;AUTO_SERVER=FALSE";
            db = DriverManager.getConnection(url, "sa", "");
            try (Statement st = db.createStatement()) {
                st.execute("""
                    CREATE TABLE IF NOT EXISTS player_distance (
                        uuid VARCHAR(36) PRIMARY KEY,
                        view INT NOT NULL,
                        simulation INT
                    )""");
                st.execute("""
                    CREATE TABLE IF NOT EXISTS group_distance (
                        group_name VARCHAR(64) PRIMARY KEY,
                        view INT NOT NULL,
                        simulation INT
                    )""");
            }
        } catch (Exception e) {
            getLogger().severe("Failed to initialize H2 database: " + e.getMessage());
        }
    }

    record DistanceData(int view, Integer simulation) {}

    private Optional<DistanceData> getPlayerData(UUID uuid) {
        try (PreparedStatement ps = db.prepareStatement("SELECT view, simulation FROM player_distance WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                int sim = rs.getInt("simulation");
                return Optional.of(new DistanceData(rs.getInt("view"), rs.wasNull() ? null : sim));
            }
        } catch (SQLException e) { getLogger().warning(e.getMessage()); }
        return Optional.empty();
    }

    private void setPlayerData(UUID uuid, int view, Integer simulation) {
        try (PreparedStatement ps = db.prepareStatement(
                "MERGE INTO player_distance (uuid, view, simulation) KEY(uuid) VALUES (?,?,?)")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, view);
            if (simulation != null) ps.setInt(3, simulation); else ps.setNull(3, Types.INTEGER);
            ps.executeUpdate();
        } catch (SQLException e) { getLogger().warning(e.getMessage()); }
    }

    private void deletePlayerData(UUID uuid) {
        try (PreparedStatement ps = db.prepareStatement("DELETE FROM player_distance WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) { getLogger().warning(e.getMessage()); }
    }

    private Optional<DistanceData> getGroupData(String group) {
        try (PreparedStatement ps = db.prepareStatement("SELECT view, simulation FROM group_distance WHERE group_name=?")) {
            ps.setString(1, group);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                int sim = rs.getInt("simulation");
                return Optional.of(new DistanceData(rs.getInt("view"), rs.wasNull() ? null : sim));
            }
        } catch (SQLException e) { getLogger().warning(e.getMessage()); }
        return Optional.empty();
    }

    private void setGroupData(String group, int view, Integer simulation) {
        try (PreparedStatement ps = db.prepareStatement(
                "MERGE INTO group_distance (group_name, view, simulation) KEY(group_name) VALUES (?,?,?)")) {
            ps.setString(1, group);
            ps.setInt(2, view);
            if (simulation != null) ps.setInt(3, simulation); else ps.setNull(3, Types.INTEGER);
            ps.executeUpdate();
        } catch (SQLException e) { getLogger().warning(e.getMessage()); }
    }

    private void deleteGroupData(String group) {
        try (PreparedStatement ps = db.prepareStatement("DELETE FROM group_distance WHERE group_name=?")) {
            ps.setString(1, group);
            ps.executeUpdate();
        } catch (SQLException e) { getLogger().warning(e.getMessage()); }
    }

    private int serverDefaultView() {
        return Bukkit.getViewDistance();
    }

    private int serverDefaultSim() {
        return Bukkit.getSimulationDistance();
    }

    private void applyToPlayer(Player p, int view, Integer sim) {
        p.setViewDistance(view);
        if (sim != null) p.setSimulationDistance(sim);
    }

    private void applyBestDistance(Player p) {
        Optional<DistanceData> personal = getPlayerData(p.getUniqueId());
        if (personal.isPresent()) {
            applyToPlayer(p, personal.get().view(), personal.get().simulation());
            return;
        }
        LuckPerms lp = getLuckPerms();
        if (lp != null) {
            String primaryGroup = lp.getPlayerAdapter(Player.class).getUser(p).getPrimaryGroup();
            Optional<DistanceData> gd = getGroupData(primaryGroup);
            if (gd.isPresent()) {
                applyToPlayer(p, gd.get().view(), gd.get().simulation());
                return;
            }
            if (getConfig().contains("groups." + primaryGroup + ".view")) {
                int v = getConfig().getInt("groups." + primaryGroup + ".view");
                Integer s = getConfig().contains("groups." + primaryGroup + ".simulation")
                        ? getConfig().getInt("groups." + primaryGroup + ".simulation") : null;
                applyToPlayer(p, v, s);
                return;
            }
        }
        p.setViewDistance(serverDefaultView());
        p.setSimulationDistance(serverDefaultSim());
    }

    private boolean validateRange(CommandSender s, int val, String field) {
        int min = getConfig().getInt("settings.min-" + field + "-distance", 2);
        int max = getConfig().getInt("settings.max-" + field + "-distance", 32);
        if (val < min || val > max) {
            send(s, "errors.out-of-range",
                    Placeholder.unparsed("min", String.valueOf(min)),
                    Placeholder.unparsed("max", String.valueOf(max)));
            return false;
        }
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        applyBestDistance(e.getPlayer());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("ppd.use")) { send(sender, "errors.no-permission"); return true; }
        if (args.length == 0) { send(sender, "errors.usage-main"); return true; }

        switch (args[0].toLowerCase()) {
            case "set" -> cmdSet(sender, args);
            case "reset" -> cmdReset(sender, args);
            case "group" -> cmdGroup(sender, args);
            case "reload" -> {
                if (!sender.hasPermission("ppd.reload")) { send(sender, "errors.no-permission"); return true; }
                reloadConfig();
                loadMessages();
                send(sender, "reload.success");
            }
            default -> send(sender, "errors.usage-main");
        }
        return true;
    }

    private void cmdSet(CommandSender s, String[] args) {
        if (!s.hasPermission("ppd.set")) { send(s, "errors.no-permission"); return; }
        if (args.length < 3) { send(s, "errors.usage-set"); return; }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) { send(s, "player.not-found", Placeholder.unparsed("player", args[1])); return; }

        int view;
        try { view = Integer.parseInt(args[2]); }
        catch (NumberFormatException e) { send(s, "errors.invalid-number", Placeholder.unparsed("value", args[2])); return; }
        if (!validateRange(s, view, "view")) return;

        Integer sim = null;
        if (args.length >= 4) {
            try { sim = Integer.parseInt(args[3]); }
            catch (NumberFormatException e) { send(s, "errors.invalid-number", Placeholder.unparsed("value", args[3])); return; }
            if (!validateRange(s, sim, "simulation")) return;
        }

        if (sim == null) {
            sim = getPlayerData(target.getUniqueId()).map(DistanceData::simulation).orElse(null);
        }

        setPlayerData(target.getUniqueId(), view, sim);
        applyToPlayer(target, view, sim);

        send(s, "player.set-view",
                Placeholder.unparsed("player", target.getName()),
                Placeholder.unparsed("view", String.valueOf(view)));
        if (args.length >= 4) {
            send(s, "player.set-simulation",
                    Placeholder.unparsed("player", target.getName()),
                    Placeholder.unparsed("sim", String.valueOf(sim)));
        }
    }

    private void cmdReset(CommandSender s, String[] args) {
        if (!s.hasPermission("ppd.reset")) { send(s, "errors.no-permission"); return; }
        if (args.length < 2) { send(s, "errors.usage-reset"); return; }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) { send(s, "player.not-found", Placeholder.unparsed("player", args[1])); return; }

        deletePlayerData(target.getUniqueId());
        applyBestDistance(target);
        send(s, "player.reset", Placeholder.unparsed("player", target.getName()));
    }

    private void cmdGroup(CommandSender s, String[] args) {
        if (!s.hasPermission("ppd.group")) { send(s, "errors.no-permission"); return; }
        LuckPerms lp = getLuckPerms();
        if (lp == null) { send(s, "errors.luckperms-not-loaded"); return; }

        if (args.length < 3) { send(s, "errors.usage-group-set"); return; }

        String groupName = args[1];
        Group lpGroup = lp.getGroupManager().getGroup(groupName);
        if (lpGroup == null) { send(s, "group.not-found", Placeholder.unparsed("group", groupName)); return; }

        switch (args[2].toLowerCase()) {
            case "set" -> {
                if (args.length < 4) { send(s, "errors.usage-group-set"); return; }
                int view;
                try { view = Integer.parseInt(args[3]); }
                catch (NumberFormatException e) { send(s, "errors.invalid-number", Placeholder.unparsed("value", args[3])); return; }
                if (!validateRange(s, view, "view")) return;

                Integer sim = null;
                if (args.length >= 5) {
                    try { sim = Integer.parseInt(args[4]); }
                    catch (NumberFormatException e) { send(s, "errors.invalid-number", Placeholder.unparsed("value", args[4])); return; }
                    if (!validateRange(s, sim, "simulation")) return;
                }

                if (sim == null) sim = getGroupData(groupName).map(DistanceData::simulation).orElse(null);

                setGroupData(groupName, view, sim);
                final int finalView = view; final Integer finalSim = sim;
                Bukkit.getOnlinePlayers().stream()
                        .filter(p -> lp.getPlayerAdapter(Player.class).getUser(p).getPrimaryGroup().equals(groupName))
                        .filter(p -> getPlayerData(p.getUniqueId()).isEmpty())
                        .forEach(p -> applyToPlayer(p, finalView, finalSim));

                send(s, "group.set-view",
                        Placeholder.unparsed("group", groupName),
                        Placeholder.unparsed("view", String.valueOf(view)));
                if (args.length >= 5) {
                    send(s, "group.set-simulation",
                            Placeholder.unparsed("group", groupName),
                            Placeholder.unparsed("sim", String.valueOf(sim)));
                }
            }
            case "reset" -> {
                deleteGroupData(groupName);
                Bukkit.getOnlinePlayers().stream()
                        .filter(p -> lp.getPlayerAdapter(Player.class).getUser(p).getPrimaryGroup().equals(groupName))
                        .filter(p -> getPlayerData(p.getUniqueId()).isEmpty())
                        .forEach(this::applyBestDistance);
                send(s, "group.reset", Placeholder.unparsed("group", groupName));
            }
            default -> send(s, "errors.usage-group-set");
        }
    }


    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("ppd.use")) return List.of();

        return switch (args.length) {
            case 1 -> filter(List.of("set", "reset", "group", "reload"), args[0]);
            case 2 -> {
                if (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("reset"))
                    yield filter(onlinePlayerNames(), args[1]);
                if (args[0].equalsIgnoreCase("group"))
                    yield filter(luckPermsGroups(), args[1]);
                yield List.of();
            }
            case 3 -> {
                if (args[0].equalsIgnoreCase("group"))
                    yield filter(List.of("set", "reset"), args[2]);
                if (args[0].equalsIgnoreCase("set"))
                    yield filter(chunkSuggestions(), args[2]);
                yield List.of();
            }
            case 4 -> {
                if (args[0].equalsIgnoreCase("set"))
                    yield filter(chunkSuggestions(), args[3]);
                if (args[0].equalsIgnoreCase("group") && args[2].equalsIgnoreCase("set"))
                    yield filter(chunkSuggestions(), args[3]);
                yield List.of();
            }
            case 5 -> {
                if (args[0].equalsIgnoreCase("group") && args[2].equalsIgnoreCase("set"))
                    yield filter(chunkSuggestions(), args[4]);
                yield List.of();
            }
            default -> List.of();
        };
    }

    private List<String> filter(List<String> list, String prefix) {
        return list.stream().filter(s -> s.toLowerCase().startsWith(prefix.toLowerCase())).collect(Collectors.toList());
    }

    private List<String> onlinePlayerNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
    }

    private List<String> chunkSuggestions() {
        return List.of("2", "4", "6", "8", "10", "12", "16", "20", "24", "32");
    }

    private List<String> luckPermsGroups() {
        LuckPerms lp = getLuckPerms();
        if (lp == null) return List.of();
        return lp.getGroupManager().getLoadedGroups().stream().map(Group::getName).collect(Collectors.toList());
    }

    private LuckPerms getLuckPerms() {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) return null;
        return getServer().getServicesManager().load(LuckPerms.class);
    }

    final class PpdExpansion extends PlaceholderExpansion {

        private final PerPlayerDistance plugin;

        PpdExpansion(PerPlayerDistance plugin) { this.plugin = plugin; }

        @Override public @NotNull String getIdentifier() { return "ppd"; }
        @Override public @NotNull String getAuthor() { return "Im_Pishi"; }
        @Override public @NotNull String getVersion() { return plugin.getDescription().getVersion(); }
        @Override public boolean persist() { return true; }

        @Override
        public String onPlaceholderRequest(Player p, @NotNull String params) {
            if (p == null) return "";
            return switch (params.toLowerCase()) {
                case "view" -> String.valueOf(p.getViewDistance());
                case "sim" -> String.valueOf(p.getSimulationDistance());
                case "group" -> {
                    LuckPerms lp = getLuckPerms();
                    yield lp == null ? "" : lp.getPlayerAdapter(Player.class).getUser(p).getPrimaryGroup();
                }
                case "group_view" -> {
                    LuckPerms lp = getLuckPerms();
                    if (lp == null) yield "";
                    String g = lp.getPlayerAdapter(Player.class).getUser(p).getPrimaryGroup();
                    yield plugin.getGroupData(g)
                            .map(d -> String.valueOf(d.view()))
                            .orElseGet(() -> plugin.getConfig().contains("groups." + g + ".view")
                                    ? String.valueOf(plugin.getConfig().getInt("groups." + g + ".view")) : "");
                }
                case "group_sim" -> {
                    LuckPerms lp = getLuckPerms();
                    if (lp == null) yield "";
                    String g = lp.getPlayerAdapter(Player.class).getUser(p).getPrimaryGroup();
                    yield plugin.getGroupData(g)
                            .filter(d -> d.simulation() != null)
                            .map(d -> String.valueOf(d.simulation()))
                            .orElseGet(() -> plugin.getConfig().contains("groups." + g + ".simulation")
                                    ? String.valueOf(plugin.getConfig().getInt("groups." + g + ".simulation")) : "");
                }
                default -> null;
            };
        }
    }
}

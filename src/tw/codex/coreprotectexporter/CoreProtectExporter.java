package tw.codex.coreprotectexporter;

import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import net.coreprotect.CoreProtectAPI.ParseResult;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Location;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;

public final class CoreProtectExporter extends JavaPlugin implements CommandExecutor {
    private static final int DEFAULT_ROWS_PER_FILE = 50_000;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter ROW_TIME = DateTimeFormatter.ISO_INSTANT;

    @Override
    public void onEnable() {
        if (getCommand("coexport") == null) {
            getLogger().severe("Missing coexport command declaration.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getCommand("coexport").setExecutor(this);
        getLogger().info("Ready. Use coexport t:30d a:+block i:end_crystal r:100 center:<online-player> from console.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("coreprotectexport.use")) {
            sender.sendMessage("You do not have permission to export CoreProtect lookups.");
            return true;
        }
        if (args.length == 0 || Arrays.stream(args).anyMatch("help"::equalsIgnoreCase)) {
            sendHelp(sender);
            return true;
        }

        Query query;
        try {
            query = parseQuery(sender, args);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage("Export query error: " + exception.getMessage());
            sendHelp(sender);
            return true;
        }

        CoreProtectAPI api = getCoreProtectApi();
        if (api == null) {
            sender.sendMessage("CoreProtect is not loaded or its API is disabled.");
            return true;
        }

        sender.sendMessage("Starting one CoreProtect lookup. Results will be written to plugins/CoreProtect/exports.");
        getServer().getScheduler().runTaskAsynchronously(this, () -> runExport(sender, api, query));
        return true;
    }

    private CoreProtectAPI getCoreProtectApi() {
        Plugin plugin = getServer().getPluginManager().getPlugin("CoreProtect");
        if (!(plugin instanceof CoreProtect coreProtect)) {
            return null;
        }
        CoreProtectAPI api = coreProtect.getAPI();
        return api != null && api.isEnabled() && api.APIVersion() >= 10 ? api : null;
    }

    private Query parseQuery(CommandSender sender, String[] args) {
        Map<String, String> values = new TreeMap<>();
        List<String> original = new ArrayList<>();
        for (String arg : args) {
            int separator = arg.indexOf(':');
            if (separator < 1 || separator == arg.length() - 1) {
                throw new IllegalArgumentException("Expected key:value, got '" + arg + "'.");
            }
            String key = arg.substring(0, separator).toLowerCase(Locale.ROOT);
            String value = arg.substring(separator + 1);
            switch (key) {
                case "t" -> key = "time";
                case "a" -> key = "action";
                case "i" -> key = "include";
                case "e" -> key = "exclude";
                case "u" -> key = "user";
                case "r" -> key = "radius";
            }
            if (values.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Condition '" + key + "' was supplied more than once.");
            }
            original.add(key + ":" + value);
        }

        int time = parseDuration(values.getOrDefault("time", "0"));
        if (time == 0) {
            throw new IllegalArgumentException("time must be greater than zero to avoid an unlimited database export.");
        }
        int radius = parseNonNegativeInt(values.getOrDefault("radius", "0"), "radius");
        int rowsPerFile = parsePositiveInt(values.getOrDefault("split", Integer.toString(DEFAULT_ROWS_PER_FILE)), "split");
        if (rowsPerFile > 1_000_000) {
            throw new IllegalArgumentException("split cannot exceed 1000000 rows per file.");
        }

        List<Integer> actions = parseActions(values.get("action"));
        List<Object> include = parseObjects(values.get("include"));
        List<Object> exclude = parseObjects(values.get("exclude"));
        List<String> users = values.containsKey("user") ? splitValues(values.get("user")) : null;
        List<String> excludeUsers = values.containsKey("exclude-user") ? splitValues(values.get("exclude-user")) : null;

        Location center = parseCenter(sender, values, radius);
        return new Query(time, users, excludeUsers, include, exclude, actions, radius, center,
                rowsPerFile, String.join(" ", original));
    }

    private Location parseCenter(CommandSender sender, Map<String, String> values, int radius) {
        String centerArg = values.get("center");
        String worldArg = values.get("world");
        boolean hasCoordinates = values.containsKey("x") || values.containsKey("y") || values.containsKey("z");

        if (centerArg != null) {
            if (worldArg != null || hasCoordinates) {
                throw new IllegalArgumentException("Use either center:<player|world,x,y,z> or world/x/y/z, not both.");
            }
            String[] fields = centerArg.split(",", -1);
            if (fields.length == 1) {
                Player player = Bukkit.getPlayerExact(fields[0]);
                if (player == null) {
                    throw new IllegalArgumentException("center player must be online: " + fields[0]);
                }
                return player.getLocation().clone();
            }
            if (fields.length == 4) {
                return makeLocation(fields[0], fields[1], fields[2], fields[3]);
            }
            throw new IllegalArgumentException("center must be an online player or world,x,y,z.");
        }

        if (hasCoordinates || worldArg != null) {
            if (worldArg == null || !values.containsKey("x") || !values.containsKey("y") || !values.containsKey("z")) {
                throw new IllegalArgumentException("Specify all of world:<world> x:<x> y:<y> z:<z>.");
            }
            return makeLocation(worldArg, values.get("x"), values.get("y"), values.get("z"));
        }

        if (sender instanceof Player player) {
            return player.getLocation().clone();
        }
        if (radius > 0) {
            throw new IllegalArgumentException("Console radius lookups need center:<online-player> or world/x/y/z.");
        }
        return null;
    }

    private Location makeLocation(String worldName, String xValue, String yValue, String zValue) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            throw new IllegalArgumentException("World is not loaded: " + worldName);
        }
        try {
            double x = Double.parseDouble(xValue);
            double y = Double.parseDouble(yValue);
            double z = Double.parseDouble(zValue);
            return new Location(world, x, y, z);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Coordinates must be numbers.");
        }
    }

    private int parseDuration(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).trim();
        int unitPosition = 0;
        while (unitPosition < normalized.length() && Character.isDigit(normalized.charAt(unitPosition))) {
            unitPosition++;
        }
        if (unitPosition == 0) {
            throw new IllegalArgumentException("time must look like 30d, 12h, 45m, or 120s.");
        }
        long amount;
        try {
            amount = Long.parseLong(normalized.substring(0, unitPosition));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("time value is too large.");
        }
        String unit = normalized.substring(unitPosition);
        long multiplier = switch (unit) {
            case "s", "sec", "secs", "second", "seconds" -> 1;
            case "m", "min", "mins", "minute", "minutes" -> 60;
            case "h", "hr", "hrs", "hour", "hours" -> 3_600;
            case "d", "day", "days" -> 86_400;
            case "w", "week", "weeks" -> 604_800;
            default -> throw new IllegalArgumentException("unsupported time unit in '" + value + "'.");
        };
        long seconds = amount * multiplier;
        if (seconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("time is too large.");
        }
        return (int) seconds;
    }

    private int parseNonNegativeInt(String value, String name) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new IllegalArgumentException(name + " cannot be negative.");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a whole number.");
        }
    }

    private int parsePositiveInt(String value, String name) {
        int parsed = parseNonNegativeInt(value, name);
        if (parsed == 0) {
            throw new IllegalArgumentException(name + " must be greater than zero.");
        }
        return parsed;
    }

    private List<Integer> parseActions(String value) {
        if (value == null) {
            return null;
        }
        List<Integer> actions = new ArrayList<>();
        for (String action : splitValues(value)) {
            switch (action.toLowerCase(Locale.ROOT)) {
                case "block" -> { addUnique(actions, 0); addUnique(actions, 1); }
                case "+block", "place", "block_place" -> addUnique(actions, 1);
                case "-block", "break", "block_break" -> addUnique(actions, 0);
                case "interaction", "interact" -> addUnique(actions, 2);
                case "kill", "entity_kill" -> addUnique(actions, 3);
                case "container" -> addUnique(actions, 4);
                case "chat" -> addUnique(actions, 6);
                case "command" -> addUnique(actions, 7);
                case "session" -> addUnique(actions, 8);
                case "username" -> addUnique(actions, 9);
                case "sign" -> addUnique(actions, 10);
                case "item", "inventory" -> addUnique(actions, 11);
                default -> throw new IllegalArgumentException("unsupported action '" + action + "'.");
            }
        }
        return actions;
    }

    private List<Object> parseObjects(String value) {
        if (value == null) {
            return null;
        }
        List<Object> objects = new ArrayList<>();
        for (String entry : splitValues(value)) {
            String name = entry;
            int namespaceSeparator = name.indexOf(':');
            if (namespaceSeparator >= 0) {
                name = name.substring(namespaceSeparator + 1);
            }
            try {
                objects.add(EntityType.valueOf(name.replace('-', '_').toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                // Use the modern material resolver. Asking Bukkit to resolve legacy
                // names can initialize its legacy-material bridge and reject valid
                // namespaced/current ids such as grass_block on recent Paper builds.
                Material material = Material.matchMaterial(name);
                if (material == null) {
                    throw new IllegalArgumentException("unknown block or entity '" + entry + "'.");
                }
                objects.add(material);
            }
        }
        return objects;
    }

    private List<String> splitValues(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    private void addUnique(List<Integer> values, int value) {
        if (!values.contains(value)) {
            values.add(value);
        }
    }

    private void runExport(CommandSender sender, CoreProtectAPI api, Query query) {
        try {
            List<String[]> rows = api.performLookup(query.timeSeconds, query.users, query.excludeUsers,
                    query.include, query.exclude, query.actions, query.radius, query.center);
            if (rows == null) {
                sender.sendMessage("CoreProtect did not return lookup results (API disabled).");
                return;
            }
            Path exportDirectory = apiExportDirectory();
            Files.createDirectories(exportDirectory);
            int total = rows.size();
            int parts = Math.max(1, (int) Math.ceil(total / (double) query.rowsPerFile));
            String timestamp = FILE_TIME.format(Instant.now());
            for (int part = 0; part < parts; part++) {
                int start = part * query.rowsPerFile;
                int end = Math.min(total, start + query.rowsPerFile);
                String fileName = "lookup_" + timestamp + "_part-" + String.format(Locale.ROOT, "%03d", part + 1) + ".tsv";
                Path file = exportDirectory.resolve(fileName);
                writePart(file, rows, start, end, part + 1, parts, query, api);
            }
            String message = "Export complete: " + total + " rows across " + parts + " file(s) in " + exportDirectory.toAbsolutePath();
            getLogger().info("Exported " + total + " CoreProtect rows across " + parts + " file(s) to " + exportDirectory.toAbsolutePath());
            getServer().getScheduler().runTask(this, () -> sender.sendMessage(message));
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "CoreProtect export failed.", exception);
            String message = "Export failed: " + exception.getMessage();
            getServer().getScheduler().runTask(this, () -> sender.sendMessage(message));
        }
    }

    private Path apiExportDirectory() {
        Plugin coreProtect = getServer().getPluginManager().getPlugin("CoreProtect");
        Path dataFolder = coreProtect == null ? getDataFolder().toPath() : coreProtect.getDataFolder().toPath();
        return dataFolder.resolve("exports");
    }

    private void writePart(Path file, List<String[]> rows, int start, int end, int part, int parts,
                           Query query, CoreProtectAPI api) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("# CoreProtect lookup export\n");
            writer.write("# query=" + sanitize(query.original) + "\n");
            writer.write("# generated_utc=" + ROW_TIME.format(Instant.now()) + "\n");
            writer.write("# part=" + part + "/" + parts + " rows=" + (end - start) + " total=" + rows.size() + "\n");
            writer.write("timestamp_utc\tplayer\taction\tobject\tworld\tx\ty\tz\trolled_back\traw\n");
            for (int index = start; index < end; index++) {
                String[] raw = rows.get(index);
                ParseResult result = api.parseResult(raw);
                String object = result.getType() == null ? "" : result.getType().getKey().toString();
                writer.write(ROW_TIME.format(Instant.ofEpochMilli(result.getTimestamp())));
                writer.write('\t'); writer.write(sanitize(result.getPlayer()));
                writer.write('\t'); writer.write(sanitize(result.getActionString()));
                writer.write('\t'); writer.write(sanitize(object));
                writer.write('\t'); writer.write(sanitize(result.worldName()));
                writer.write('\t'); writer.write(Integer.toString(result.getX()));
                writer.write('\t'); writer.write(Integer.toString(result.getY()));
                writer.write('\t'); writer.write(Integer.toString(result.getZ()));
                writer.write('\t'); writer.write(Boolean.toString(result.isRolledBack()));
                writer.write('\t'); writer.write(sanitize(String.join("|", raw)));
                writer.newLine();
            }
        }
    }

    private String sanitize(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("CoreProtect export: coexport time:30d action:+block include:end_crystal radius:100 center:<player>");
        sender.sendMessage("Console center options: center:<online-player> OR world:<world> x:<x> y:<y> z:<z>.");
        sender.sendMessage("In-game commands use your current location when radius is set. Also accepts t/a/i/e/u/r aliases.");
        sender.sendMessage("split:<rows> controls rows per file (default 50000). Exports go to plugins/CoreProtect/exports.");
    }

    private record Query(int timeSeconds, List<String> users, List<String> excludeUsers,
                         List<Object> include, List<Object> exclude, List<Integer> actions,
                         int radius, Location center, int rowsPerFile, String original) {}
}

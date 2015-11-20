package fr.xephi.authme.util;

import com.maxmind.geoip.LookupService;
import fr.xephi.authme.AuthMe;
import fr.xephi.authme.ConsoleLogger;
import fr.xephi.authme.cache.auth.PlayerCache;
import fr.xephi.authme.cache.limbo.LimboCache;
import fr.xephi.authme.cache.limbo.LimboPlayer;
import fr.xephi.authme.events.AuthMeTeleportEvent;
import fr.xephi.authme.settings.Settings;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.io.*;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.zip.GZIPInputStream;

public class Utils {

    public static AuthMe plugin;

    private static boolean getOnlinePlayersIsCollection;
    private static Method getOnlinePlayers;
    private static LookupService lookupService;

    static {
        plugin = AuthMe.getInstance();
        checkGeoIP();
        try {
            Method m = Bukkit.class.getDeclaredMethod("getOnlinePlayers");
            getOnlinePlayersIsCollection = m.getReturnType() == Collection.class;
        } catch (Exception ignored) {
        }
    }

    // Check and Download GeoIP data if not exist
    public static boolean checkGeoIP() {
        if (lookupService != null) {
            return true;
        }
        final File data = new File(Settings.PLUGIN_FOLDER, "GeoIP.dat");
        if (data.exists()) {
            if (lookupService == null) {
                try {
                    lookupService = new LookupService(data);
                    ConsoleLogger.info("[LICENSE] This product uses data from the GeoLite API created by MaxMind, available at http://www.maxmind.com");
                    return true;
                } catch (IOException e) {
                    return false;
                }
            }
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                try {
                    String url = "http://geolite.maxmind.com/download/geoip/database/GeoLiteCountry/GeoIP.dat.gz";
                    URL downloadUrl = new URL(url);
                    URLConnection conn = downloadUrl.openConnection();
                    conn.setConnectTimeout(10000);
                    conn.connect();
                    InputStream input = conn.getInputStream();
                    if (conn.getURL().toString().endsWith(".gz")) {
                        input = new GZIPInputStream(input);
                    }
                    OutputStream output = new FileOutputStream(data);
                    byte[] buffer = new byte[2048];
                    int length = input.read(buffer);
                    while (length >= 0) {
                        output.write(buffer, 0, length);
                        length = input.read(buffer);
                    }
                    output.close();
                    input.close();
                } catch (IOException e) {
                    ConsoleLogger.writeStackTrace(e);
                }
            }
        });
        return false;
    }

    public static String getCountryCode(String ip) {
        if (checkGeoIP()) {
            return lookupService.getCountry(ip).getCode();
        }
        return "--";
    }

    public static String getCountryName(String ip) {
        if (checkGeoIP()) {
            return lookupService.getCountry(ip).getName();
        }
        return "N/A";
    }

    public static void setGroup(Player player, GroupType group) {
        if (!Settings.isPermissionCheckEnabled)
            return;
        if (plugin.vaultGroupManagement == null)
            return;
        String currentGroup;
        try {
            currentGroup = plugin.vaultGroupManagement.getPrimaryGroup(player);
        } catch (UnsupportedOperationException e) {
            ConsoleLogger.showError("Your permission plugin (" + plugin.vaultGroupManagement.getName() + ") doesn't support the Group system... unhook!");
            plugin.vaultGroupManagement = null;
            return;
        }
        switch (group) {
            case UNREGISTERED: {
                plugin.vaultGroupManagement.playerRemoveGroup(player, currentGroup);
                plugin.vaultGroupManagement.playerAddGroup(player, Settings.unRegisteredGroup);
                break;
            }
            case REGISTERED: {
                plugin.vaultGroupManagement.playerRemoveGroup(player, currentGroup);
                plugin.vaultGroupManagement.playerAddGroup(player, Settings.getRegisteredGroup);
                break;
            }
            case NOTLOGGEDIN: {
                if (!useGroupSystem())
                    break;
                plugin.vaultGroupManagement.playerRemoveGroup(player, currentGroup);
                plugin.vaultGroupManagement.playerAddGroup(player, Settings.getUnloggedinGroup);
                break;
            }
            case LOGGEDIN: {
                if (!useGroupSystem())
                    break;
                LimboPlayer limbo = LimboCache.getInstance().getLimboPlayer(player.getName().toLowerCase());
                if (limbo == null)
                    break;
                String realGroup = limbo.getGroup();
                plugin.vaultGroupManagement.playerRemoveGroup(player, currentGroup);
                plugin.vaultGroupManagement.playerAddGroup(player, realGroup);
                break;
            }
        }
    }

    public static boolean addNormal(Player player, String group) {
        if (!useGroupSystem()) {
            return false;
        }
        if (plugin.vaultGroupManagement == null)
            return false;
        try {
            if (plugin.vaultGroupManagement.playerRemoveGroup(player, Settings.getUnloggedinGroup) && plugin.vaultGroupManagement.playerAddGroup(player, group)) {
                return true;
            }
        } catch (UnsupportedOperationException e) {
            ConsoleLogger.showError("Your permission system (" + plugin.vaultGroupManagement.getName() + ") do not support Group system with that config... unhook!");
            plugin.vaultGroupManagement = null;
            return false;
        }
        return false;
    }

    // TODO: Move to a Manager
    public static boolean checkAuth(Player player) {
        if (player == null || Utils.isUnrestricted(player)) {
            return true;
        }

        String name = player.getName().toLowerCase();
        if (PlayerCache.getInstance().isAuthenticated(name)) {
            return true;
        }

        if (!Settings.isForcedRegistrationEnabled) {
            if (!plugin.database.isAuthAvailable(name)) {
                return true;
            }
        }

        return false;
    }

    public static boolean isUnrestricted(Player player) {
        return Settings.isAllowRestrictedIp && !Settings.getUnrestrictedName.isEmpty()
                && (Settings.getUnrestrictedName.contains(player.getName()));
    }

    private static boolean useGroupSystem() {
        return Settings.isPermissionCheckEnabled && !Settings.getUnloggedinGroup.isEmpty();
    }

    public static void packCoords(double x, double y, double z, String w,
                                  final Player pl) {
        World theWorld;
        if (w.equals("unavailableworld")) {
            theWorld = pl.getWorld();
        } else {
            theWorld = Bukkit.getWorld(w);
        }
        if (theWorld == null)
            theWorld = pl.getWorld();
        final World world = theWorld;
        final Location loc = new Location(world, x, y, z);

        Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, new Runnable() {

            @Override
            public void run() {
                AuthMeTeleportEvent tpEvent = new AuthMeTeleportEvent(pl, loc);
                plugin.getServer().getPluginManager().callEvent(tpEvent);
                if (!tpEvent.isCancelled()) {
                    pl.teleport(tpEvent.getTo());
                }
            }
        });
    }

    /*
     * Used for force player GameMode
     */
    public static void forceGM(Player player) {
        if (!plugin.getPermissionsManager().hasPermission(player, "authme.bypassforcesurvival"))
            player.setGameMode(GameMode.SURVIVAL);
    }

    public enum GroupType {
        UNREGISTERED,
        REGISTERED,
        NOTLOGGEDIN,
        LOGGEDIN
    }

    public static void purgeDirectory(File file) {
        if (!file.isDirectory()) {
            return;
        }
        File[] files = file.listFiles();
        if (files == null) {
            return;
        }
        for (File target : files) {
            if (target.isDirectory()) {
                purgeDirectory(target);
                target.delete();
            } else {
                target.delete();
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static Collection<? extends Player> getOnlinePlayers() {
        if (getOnlinePlayersIsCollection) {
            return Bukkit.getOnlinePlayers();
        }
        try {
            if (getOnlinePlayers == null) {
                getOnlinePlayers = Bukkit.class.getMethod("getOnlinePlayers");
            }
            Object obj = getOnlinePlayers.invoke(null);
            if (obj instanceof Collection) {
                return (Collection<? extends Player>) obj;
            }
            return Arrays.asList((Player[]) obj);
        } catch (Exception ignored) {
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("deprecation")
    public static Player getPlayer(String name) {
        name = name.toLowerCase();
        return plugin.getServer().getPlayer(name);
    }

    public static boolean isNPC(final Entity player) {
        try {
            if (player.hasMetadata("NPC")) {
                return true;
            } else if (plugin.combatTagPlus != null
                    && player instanceof Player
                    && plugin.combatTagPlus.getNpcPlayerHelper().isNpc((Player) player)) {
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public static void teleportToSpawn(Player player) {
        if (Settings.isTeleportToSpawnEnabled && !Settings.noTeleport) {
            Location spawn = plugin.getSpawnLocation(player);
            AuthMeTeleportEvent tpEvent = new AuthMeTeleportEvent(player, spawn);
            plugin.getServer().getPluginManager().callEvent(tpEvent);
            if (!tpEvent.isCancelled()) {
                player.teleport(tpEvent.getTo());
            }
        }
    }
}

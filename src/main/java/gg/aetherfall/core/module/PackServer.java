package gg.aetherfall.core.module;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import gg.aetherfall.core.AetherCore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * Built-in lightweight HTTP server to host and serve the Aetherfall resource pack
 * on a user-configurable port.
 */
public final class PackServer {
    private final AetherCore plugin;
    private HttpServer server;
    private ExecutorService executor;
    private int port = 8100;
    private String host = "0.0.0.0";
    private boolean hostServer = true;
    private String externalIp = "";
    private String customUrl = "";

    private String cachedHash;
    private long cachedStamp;

    public PackServer(AetherCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        stop();
        var cfg = plugin.getConfig();
        this.hostServer = cfg.getBoolean("resource-pack.host-server", true);
        this.port = cfg.getInt("resource-pack.port", 8100);
        this.host = cfg.getString("resource-pack.host", "0.0.0.0");
        this.externalIp = cfg.getString("resource-pack.external-ip", "");
        this.customUrl = cfg.getString("resource-pack.url", "");

        if (cfg.getBoolean("resource-pack.enabled", true) && hostServer) {
            start();
        }
    }

    public synchronized void start() {
        if (server != null) return;
        try {
            server = HttpServer.create(new InetSocketAddress(host, port), 0);
            server.createContext("/aetherfall-pack.zip", new PackHandler());
            server.createContext("/pack.zip", new PackHandler());
            executor = Executors.newCachedThreadPool(r -> {
                Thread thread = new Thread(r, "AetherCore-PackServer");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.start();
            plugin.getLogger().info("Resource pack HTTP server started on " + host + ":" + port);
        } catch (java.net.BindException e) {
            syncPackToBlueMap();
            plugin.getLogger().info("Resource pack port " + port + " is already in use (e.g. by BlueMap). Using the active web server on port " + port + " for pack delivery.");
            server = null;
        } catch (Exception e) {
            plugin.getLogger().warning("Could not start resource pack HTTP server on port " + port + ": " + e.getMessage());
            server = null;
        }
    }

    /** Keep a same-port BlueMap fallback in sync with the pack AetherCore generated. */
    private void syncPackToBlueMap() {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("BlueMap")) return;
        File source = new File(plugin.getDataFolder(), "aetherfall-pack.zip");
        if (!source.isFile()) source = new File(plugin.getDataFolder(), "pack.zip");
        if (!source.isFile()) return;
        File target = new File(plugin.getServer().getWorldContainer(), "bluemap/web/aetherfall-pack.zip");
        File parent = target.getParentFile();
        File temp = new File(parent, target.getName() + ".tmp");
        try {
            Files.createDirectories(parent.toPath());
            Files.copy(source.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            try { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING); }
            plugin.getLogger().info("Synchronized resource pack fallback to " + target.getPath());
        } catch (IOException ex) {
            if (temp.isFile()) temp.delete();
            plugin.getLogger().warning("Could not synchronize BlueMap resource pack fallback: " + ex.getMessage());
        }
    }

    public synchronized void stop() {
        if (server != null) {
            try {
                server.stop(0);
                plugin.getLogger().info("Resource pack HTTP server stopped.");
            } catch (Exception ignored) {
            }
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /** Finds the resource pack zip file on disk */
    public File getPackFile() {
        File dataPack = new File(plugin.getDataFolder(), "aetherfall-pack.zip");
        if (dataPack.exists()) return dataPack;

        File altDataPack = new File(plugin.getDataFolder(), "pack.zip");
        if (altDataPack.exists()) return altDataPack;

        File blueMapPack = new File(plugin.getServer().getWorldContainer(), "bluemap/web/aetherfall-pack.zip");
        if (blueMapPack.exists()) return blueMapPack;

        File rootPack = new File(plugin.getServer().getWorldContainer(), "aetherfall-pack.zip");
        if (rootPack.exists()) return rootPack;

        return dataPack;
    }

    /** Returns the resolved download URL for players */
    public String getPackUrl() {
        if (customUrl != null && !customUrl.isBlank()) {
            return customUrl;
        }

        String targetHost = externalIp;
        if (targetHost == null || targetHost.isBlank()) {
            String mapUrl = plugin.getConfig().getString("map-url", "");
            if (!mapUrl.isBlank()) {
                try {
                    URI uri = URI.create(mapUrl);
                    if (uri.getHost() != null && !uri.getHost().isBlank()) {
                        targetHost = uri.getHost();
                    }
                } catch (Exception ignored) {}
            }
        }

        if (targetHost == null || targetHost.isBlank()) {
            String serverIp = plugin.getServer().getIp();
            if (serverIp != null && !serverIp.isBlank() && !serverIp.equals("0.0.0.0")) {
                targetHost = serverIp;
            } else {
                targetHost = "localhost";
            }
        }

        return "http://" + targetHost + ":" + port + "/aetherfall-pack.zip";
    }

    /** Calculates or retrieves cached SHA-1 of the resource pack file */
    public String getPackHash() throws Exception {
        File file = getPackFile();
        if (!file.exists()) return null;
        long stamp = file.lastModified() ^ file.length();
        if (cachedHash == null || stamp != cachedStamp) {
            var md = MessageDigest.getInstance("SHA-1");
            cachedHash = HexFormat.of().formatHex(md.digest(Files.readAllBytes(file.toPath())));
            cachedStamp = stamp;
        }
        return cachedHash;
    }

    public int getPort() {
        return port;
    }

    private class PackHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            File pack = getPackFile();
            if (!pack.exists()) {
                byte[] notFound = "Resource pack file not found on server.".getBytes();
                exchange.sendResponseHeaders(404, notFound.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(notFound);
                }
                return;
            }

            long length = pack.length();
            String etag;
            try {
                etag = getPackHash();
            } catch (Exception e) {
                etag = String.valueOf(pack.lastModified());
            }

            // Check ETag
            String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
            if (etag != null && etag.equalsIgnoreCase(ifNoneMatch)) {
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }

            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"aetherfall-pack.zip\"");
            if (etag != null) exchange.getResponseHeaders().set("ETag", etag);
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

            if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, length);
                exchange.close();
                return;
            }

            exchange.sendResponseHeaders(200, length);
            try (FileInputStream fis = new FileInputStream(pack);
                 OutputStream os = exchange.getResponseBody()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
            }
        }
    }
}

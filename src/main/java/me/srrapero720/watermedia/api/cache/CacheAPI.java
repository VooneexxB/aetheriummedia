package me.srrapero720.watermedia.api.cache;

import me.srrapero720.watermedia.WaterMedia;
import me.srrapero720.watermedia.api.bootstrap.IBootstrap;
import me.srrapero720.watermedia.api.bootstrap.IModuleBootstrap;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import java.io.*;
import java.nio.file.Files;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static me.srrapero720.watermedia.WaterMedia.LOGGER;

public class CacheAPI extends IModuleBootstrap {
    private static final Marker IT = MarkerManager.getMarker(CacheAPI.class.getSimpleName());
    private static final Map<String, CacheEntry> ENTRIES = new HashMap<>();

    private static File dir;
    private static File index;
    private static boolean released = true;

    CacheAPI() {
        super();
        IBootstrap bootstrap = WaterMedia.getInstance().getBootstrap();
        dir = bootstrap.tempDir().toAbsolutePath().resolve("cache/pictures").toFile();
        index = new File(dir, "index");
    }

    @Override
    public Priority priority() {
        return Priority.NORMAL;
    }

    @Override
    public boolean prepare() {
        if (!released) {
            LOGGER.error(IT, "Failed due boot API while is not released, boot cancelled");
            return false;
        }

        if (!dir.exists() && !dir.mkdirs()) {
            LOGGER.error(IT, "Failed due making directories, boot cancelled");
            return false;
        }
        return true;
    }

    @Override
    public void start() throws Exception {
        LOGGER.info(IT, "Mounted on path '{}'", dir);
        if (index.exists()) {
            try (DataInputStream stream = new DataInputStream(new GZIPInputStream(Files.newInputStream(index.toPath())))) {
                int length = stream.readInt();

                for (int i = 0; i < length; i++) {
                    String url = stream.readUTF();
                    String tag = stream.readUTF();
                    long time = stream.readLong();
                    long expireTime = stream.readLong();
                    CacheEntry entry = new CacheEntry(url, !tag.isEmpty() ? tag : null, time, expireTime);
                    ENTRIES.put(entry.getUrl(), entry);
                }
            }
        }
        released = false;
    }

    @Override
    public void release() {
        ENTRIES.clear();
        dir = null;
        index = null;
        released = true;
    }

    /* STATIC API */
    public static CacheEntry getEntry(String url) {
        synchronized (ENTRIES) {
            return ENTRIES.get(url);
        }
    }

    public static void updateEntry(CacheEntry fresh) {
        synchronized (ENTRIES) {
            ENTRIES.put(fresh.getUrl(), fresh);
        }
    }

    public static void deleteEntry(String url) {
        synchronized (ENTRIES) {
            ENTRIES.remove(url);
            File file = entry$getFile(url);
            if (file.exists()) file.delete();
        }
    }

    public static void saveFile(String url, String tag, long time, long expireTime, byte[] data) {
        synchronized (ENTRIES) {
            CacheEntry entry = new CacheEntry(url, tag, time, expireTime);
            boolean saved = false;
            File file = entry$getFile(entry.getUrl());

            try (OutputStream out = Files.newOutputStream(file.toPath())) {
                out.write(data);
                saved = true;
            } catch (Exception e) { LOGGER.error(IT, "Failed to save cache file {}", url, e); }

            // SAVE INDEX FIST
            if (saved && updateIndex()) ENTRIES.put(url, entry);
            else if (file.exists()) file.delete();
        }
    }

    private static boolean updateIndex() {
        try(DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(index.toPath())))) {
            out.writeInt(ENTRIES.size());

            for (Map.Entry<String, CacheEntry> mapEntry : ENTRIES.entrySet()) {
                CacheEntry entry = mapEntry.getValue();
                out.writeUTF(entry.getUrl());
                out.writeUTF(entry.getTag() == null ? "" : entry.getTag());
                out.writeLong(entry.getTime());
                out.writeLong(entry.getExpireTime());
            }

            return true;
        } catch (IOException e) { LOGGER.error(IT, "Failed to refresh cache index", e); }
        return false;
    }

    static File entry$getFile(String url) {
        return new File(dir, Base64.getEncoder().encodeToString(url.getBytes()));
    }
}
import app.scoreline.core.Http;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class HttpTest {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("scoreline-http-test");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/score", exchange -> {
            hits.incrementAndGet();
            byte[] body = "Chelsea 2-1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            Http http = new Http(dir.toFile());
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/score";
            List<Callable<String>> calls = new ArrayList<>();
            for (int i = 0; i < 16; i++) calls.add(() -> http.get(url, 60_000).text());
            for (Future<String> result : pool.invokeAll(calls))
                check(result.get().equals("Chelsea 2-1"), "Concurrent response corrupted");
            check(hits.get() == 1, "Concurrent callers duplicated download");
            server.stop(0);
            check(http.get(url, 0).stale, "Offline fallback must be marked stale");
            for (int i = 0; i < 5; i++) Files.write(dir.resolve("large" + i), new byte[8 * 1024 * 1024]);
            http.prune(86_400_000);
            long total;
            try (var files = Files.list(dir)) { total = files.mapToLong(p -> p.toFile().length()).sum(); }
            check(total <= 32L * 1024 * 1024, "Cache exceeded size cap");
            Thread.currentThread().interrupt();
            try { http.get(url, 60_000); throw new AssertionError("Cancelled read proceeded"); }
            catch (java.io.InterruptedIOException expected) { }
            finally { Thread.interrupted(); }
            System.out.println("PASS: concurrent cache, offline freshness, size eviction, cancellation");
        } finally {
            pool.shutdownNow(); server.stop(0);
            try (var files = Files.list(dir)) { for (Path p : files.toList()) Files.delete(p); }
            Files.delete(dir);
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

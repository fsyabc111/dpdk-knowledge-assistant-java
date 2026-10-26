package acceptance.platform;

import static org.junit.jupiter.api.Assertions.*;
import io.dpdk.assistant.chat.ChatHttp;
import io.dpdk.assistant.contracts.chat.SseEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.*;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** 模型被闩锁阻塞时，真实TCP必须先收到start；不能以最终整段SSE冒充实时。 */
class SseFirstFrameTest {
    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName="org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
    static class Config { @Bean Probe probe() { return new Probe(); } }
    @RestController static class Probe {
        final CountDownLatch finish = new CountDownLatch(1);
        @GetMapping("/stream") ResponseEntity<StreamingResponseBody> stream() {
            return ChatHttp.stream(subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                public void cancel() { finish.countDown(); }
                public void request(long n) {
                    Thread.startVirtualThread(() -> {
                        try {
                            subscriber.onNext(new SseEvent("start","{\"task_id\":\"synthetic\"}"));
                            finish.await(10,TimeUnit.SECONDS);
                            subscriber.onNext(new SseEvent("done","{\"status\":\"answered\"}"));
                            subscriber.onComplete();
                        } catch (InterruptedException e) { Thread.currentThread().interrupt(); subscriber.onError(e); }
                    });
                }
            }));
        }
    }
    @Test void firstFrameArrivesBeforeProducerCompletes() throws Exception {
        try(var context=new SpringApplicationBuilder(Config.class).properties(Map.of(
                "server.address","127.0.0.1","server.port","0","logging.level.root","ERROR")).run()) {
            var producer=context.getBean(Probe.class);
            int port=((WebServerApplicationContext)context).getWebServer().getPort();
            try(var client=HttpClient.newHttpClient()) {
                var response=client.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/stream")).build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                try {
                    var headers=response.get(2,TimeUnit.SECONDS);
                    assertEquals(200,headers.statusCode());
                    assertEquals(1,producer.finish.getCount());
                    try(var reader=new BufferedReader(new InputStreamReader(headers.body()))) {
                        assertEquals("event: start",reader.readLine());
                    }
                } finally { producer.finish.countDown(); response.cancel(true); }
            }
        }
    }
}

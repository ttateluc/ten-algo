package com.gtc.ws;

import com.appunite.websocket.rx.RxMoreObservables;
import com.appunite.websocket.rx.RxWebSockets;
import com.appunite.websocket.rx.object.ObjectSerializer;
import com.appunite.websocket.rx.object.RxObjectWebSockets;
import com.appunite.websocket.rx.object.messages.RxObjectEvent;
import com.appunite.websocket.rx.object.messages.RxObjectEventConnected;
import com.appunite.websocket.rx.object.messages.RxObjectEventMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.gtc.rxsupport.JacksonSerializer;
import com.gtc.rxsupport.MoreObservables;
import com.newrelic.api.agent.NewRelic;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.experimental.Delegate;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.slf4j.Logger;
import rx.Observable;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.subjects.BehaviorSubject;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Created by Valentyn Berezin on 16.06.18.
 */
@Slf4j
@RequiredArgsConstructor
public class BaseWebsocketClient {

    private static final String CONNECTS = "Custom/Connect/";
    private static final String MESSAGE = "Custom/Message/";

    private final Object lock = new Object();

    @Delegate
    private final Config config;

    @Delegate
    private final Handlers handlers;

    @Getter
    private long connectedAtTimestamp;

    @Getter
    private boolean disconnected = true;

    private BehaviorSubject<Boolean> doDisconnect;

    @SneakyThrows
    public void connect(Map<String, String> headers) {
        synchronized (lock) {
            if (!disconnected) {
                return;
            }

            Action1<Throwable> handleError = err -> {
                log.info("WS error", err);
                disconnected = true;
            };

            Action0 handleDisconnect = () -> disconnected = true;

            getLog().info("Connecting");
            Request request = new Request.Builder()
                    .get()
                    .headers(Headers.of(headers))
                    .url(getWsPath())
                    .build();

            Observable<RxObjectEvent> sharedConnection = getConnection(request);

            doDisconnect = BehaviorSubject.create();
            sharedConnection
                    .compose(MoreObservables.filterAndMap(RxObjectEventConnected.class))
                    .takeUntil(doDisconnect)
                    .subscribe(onConn -> {
                        NewRelic.incrementCounter(CONNECTS + getName());
                        disconnected = false;
                        getLog().info("Connected");
                        getHandleConnected().accept(onConn);
                    }, handleError, handleDisconnect);

            sharedConnection
                    .takeUntil(doDisconnect)
                    .compose(MoreObservables.filterAndMap(RxObjectEventMessage.class))
                    .compose(RxObjectEventMessage.filterAndMap(JsonNode.class))
                    .subscribe(this::handleInboundMessage, handleError, handleDisconnect);
        }
    }

    public void disconnect() {
        synchronized (lock) {
            if (disconnected) {
                return;
            }

            doDisconnect.onNext(true);
        }
    }

    public static void sendIfNotNull(RxObjectEventConnected evt, Object msg) {
        if (null == msg) {
            return;
        }

        RxMoreObservables
                .sendObjectMessage(evt.sender(), msg)
                .subscribe();
    }

    private void handleInboundMessage(JsonNode node) {
        NewRelic.incrementCounter(MESSAGE + getName());
        try {
            if (!node.isArray()) {
                getParseJsonObject().accept(node);
            } else if (node.isArray()) {
                getParseJsonArray().accept(node);
            }
        } catch (RuntimeException ex) {
            NewRelic.noticeError(ex, ImmutableMap.of("name", getName()));
            getLog().error("Exception handling {} / {}", node, ex.getMessage(), ex);
        }
    }

    private Observable<RxObjectEvent> getConnection(Request request) {
        ObjectSerializer serializer = new JacksonSerializer(getObjectMapper());
        return new RxObjectWebSockets(new RxWebSockets(new OkHttpClient(), request), serializer)
                .webSocketObservable()
                .timeout(getDisconnectIfInactiveS(), TimeUnit.SECONDS)
                .doOnCompleted(() -> handleDisconnectEvt("Disconnected (completed)", null))
                .doOnError(throwable -> handleDisconnectEvt("Disconnected (exceptional)", throwable))
                .share();
    }

    private void handleDisconnectEvt(String reason, Throwable err) {
        synchronized (lock) {
            disconnected = true;

            if (null != err) {
                NewRelic.noticeError(err, ImmutableMap.of("name", getName()));
                getLog().error(reason, err);
            } else {
                NewRelic.noticeError(getName());
                getLog().error(reason);
            }
        }
    }

    @Getter
    @RequiredArgsConstructor
    public static class Config {
        private final String wsPath;
        private final String name;
        private final int disconnectIfInactiveS;
        private final ObjectMapper objectMapper;
        private final Logger log;
    }

    @Getter
    @RequiredArgsConstructor
    public static class Handlers {
        private final Consumer<RxObjectEventConnected> handleConnected;
        private final Consumer<JsonNode> parseJsonObject;
        private final Consumer<JsonNode> parseJsonArray;
    }
}

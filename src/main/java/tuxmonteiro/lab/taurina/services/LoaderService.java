/*
 * Copyright (c) 2017-2018 Globo.com
 * All rights reserved.
 *
 * This source is subject to the Apache License, Version 2.0.
 * Please see the LICENSE file for more information.
 *
 * Authors: See AUTHORS file
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tuxmonteiro.lab.taurina.services;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@EnableAsync
@EnableScheduling
public class LoaderService {

    private final ReportService reportService;

    enum Proto {
        HTTPS_1(true),
        HTTPS_2(true),
        HTTP_1(false),
        HTTP_2(false);

        private final boolean ssl;

        Proto(boolean ssl) {
            this.ssl = ssl;
        }

        public boolean isSsl() {
            return ssl;
        }

        public SslContext sslContext() {
            if (isSsl()) {
                try {
                    final SslProvider provider = OpenSsl.isAlpnSupported() ? SslProvider.OPENSSL : SslProvider.JDK;
                    return SslContextBuilder.forClient()
                        .sslProvider(provider)
                        /* NOTE: the cipher filter may not include all ciphers required by the HTTP/2 specification.
                         * Please refer to the HTTP/2 specification for cipher requirements. */
                        .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
                        .applicationProtocolConfig(new ApplicationProtocolConfig(
                            Protocol.ALPN,
                            // NO_ADVERTISE is currently the only mode supported by both OpenSsl and JDK providers.
                            SelectorFailureBehavior.NO_ADVERTISE,
                            // ACCEPT is currently the only mode supported by both OpenSsl and JDK providers.
                            SelectedListenerFailureBehavior.ACCEPT,
                            ApplicationProtocolNames.HTTP_2,
                            ApplicationProtocolNames.HTTP_1_1))
                        .build();
                } catch (SSLException e) {
                    LOGGER.error(e.getMessage(), e);
                }
            }
            return null;
        }

        public ChannelInitializer initializer(final ReportService reportService) {
            if (this == HTTP_2 || this == HTTPS_2) {
                return new Http2ClientInitializer(sslContext(), Integer.MAX_VALUE, reportService);
            }
            return new Http1ClientInitializer(sslContext(), reportService);
        }

        public static Proto schemaToProto(String schema) {
            switch (schema) {
                case "h2":
                    return HTTP_2;
                case "h2c":
                    return HTTPS_2;
                case "http":
                    return HTTP_1;
                case "https":
                    return HTTPS_1;
            }
            return Proto.valueOf(schema);
        }
    }

    private static final Log LOGGER = LogFactory.getLog(LoaderService.class);
    private static final boolean IS_MAC = isMac();
    private static final boolean IS_LINUX = isLinux();

    private static final int NUM_CORES = Runtime.getRuntime().availableProcessors();

    private final Object lock = new Object();

    private final int numConn = Integer.parseInt(System.getProperty("taurina.numconn", "10"));
    private final int durationSec = Integer.parseInt(System.getProperty("taurina.duration", "30"));
    private final HttpMethod method = HttpMethod.GET;
    private final String uriStr = System.getProperty("taurina.uri", "http://127.0.0.1:8030");
    private final URI uri = URI.create(uriStr);
    private final String path = System.getProperty("taurina.targetpath", "/");
    private final int threads = Integer.parseInt(System.getProperty("taurina.threads",
        String.valueOf(NUM_CORES > numConn ? numConn : NUM_CORES)));

    private final HttpHeaders headers = new DefaultHttpHeaders()
        .add(HOST, uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : ""))
        .add(HttpConversionUtil.ExtensionHeaderNames.SCHEME.text(), convertSchemeIfNecessary(uri.getScheme()));

    @Autowired
    public LoaderService(ReportService reportService) {
        this.reportService = reportService;
    }

    private String convertSchemeIfNecessary(String scheme) {
        return scheme.replace("h2c", "https").replace("h2", "http");
    }

    private final FullHttpRequest request = new DefaultFullHttpRequest(
        HttpVersion.HTTP_1_1, method, path, Unpooled.buffer(0), headers, new DefaultHttpHeaders());

    private AtomicLong start = new AtomicLong(0L);

    @Async
    @Scheduled(fixedRate = 5_000L)
    public void start() {
        if (start.get() != 0) {
            return;
        } else {
            start.set(-1);
        }
        LOGGER.info("Using " + threads + " thread(s)");

        final Proto proto = Proto.schemaToProto(uri.getScheme());
        final EventLoopGroup group = getEventLoopGroup(threads);
        final Bootstrap bootstrap = newBootstrap(group);

        try {
            Channel[] channels = new Channel[numConn];
            activeChanels(proto, bootstrap, channels);

            start.set(System.currentTimeMillis());

            reconnectIfNecessary(proto, group, bootstrap, channels);

            TimeUnit.SECONDS.sleep(durationSec);

            reportService.showReport(start.get());
            reportService.reset();

            closeChannels(group, channels, 5, TimeUnit.SECONDS);

        } catch (InterruptedException e) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(e.getMessage(), e);
            }
        } finally {
            if (!group.isShuttingDown()) {
                group.shutdownGracefully();
            }
        }
    }

    private void reconnectIfNecessary(final Proto proto, final EventLoopGroup group, Bootstrap bootstrap, Channel[] channels) {
        group.scheduleAtFixedRate(() ->
            activeChanels(proto, bootstrap, channels), 100, 100, TimeUnit.MICROSECONDS);
    }

    private Bootstrap newBootstrap(EventLoopGroup group) {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.
            group(group).
            channel(getSocketChannelClass()).
            option(ChannelOption.SO_KEEPALIVE, true).
            option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 60000).
            option(ChannelOption.TCP_NODELAY, true).
            option(ChannelOption.SO_REUSEADDR, true);
        return bootstrap;
    }

    private void closeChannels(EventLoopGroup group, Channel[] channels, int timeout, TimeUnit unit) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(channels.length - 1);
        for (Channel channel : channels) {
            group.execute(() -> {
                closeChannel(latch, channel);
            });
        }
        latch.await(timeout, unit);
    }



    private void closeChannel(final CountDownLatch latch, final Channel channel) {
        if (channel.isActive()) {
            try {
                channel.closeFuture().sync();
            } catch (Exception e) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(e.getMessage(), e);
                }
            } finally {
                latch.countDown();
            }
        }
    }

    private synchronized void activeChanels(final Proto proto, final Bootstrap bootstrap, final Channel[] channels) {
        for (int chanId = 0; chanId < numConn; chanId++) {
            if (channels[chanId] == null || !channels[chanId].isActive()) {
                Channel channel = newChannel(bootstrap, proto);
                if (channel != null) {
                    channels[chanId] = channel;
                }
            }
        }
    }

    private Channel newChannel(final Bootstrap bootstrap, Proto proto) {
        try {
            final Channel channel = bootstrap
                                        .clone()
                                        .handler(proto.initializer(reportService))
                                        .connect(uri.getHost(), uri.getPort())
                                        .sync()
                                        .channel();
            channel.eventLoop().scheduleAtFixedRate(() -> {
                if (channel.isActive()) {
                    reportService.writeAsyncIncr();
                    channel.writeAndFlush(request.copy());
                }
            }, 50, 50, TimeUnit.MICROSECONDS);
            return channel;
        } catch (InterruptedException e) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(e.getMessage(), e);
            }
        }
        return null;
    }

    private EventLoopGroup getEventLoopGroup(int numCores) {
        // @formatter:off
        return IS_MAC   ? new KQueueEventLoopGroup(numCores) :
               IS_LINUX ? new EpollEventLoopGroup(numCores) :
                          new NioEventLoopGroup(numCores);
        // @formatter:on
    }

    private Class<? extends Channel> getSocketChannelClass() {
        // @formatter:off
        return IS_MAC   ? KQueueSocketChannel.class :
               IS_LINUX ? EpollSocketChannel.class :
                          NioSocketChannel.class;
        // @formatter:on
    }

    private static String getOS() {
        return System.getProperty("os.name", "UNDEF").toLowerCase();
    }

    private static boolean isMac() {
        boolean result = getOS().startsWith("mac");
        if (result) {
            LOGGER.warn("Hello. I'm Mac");
        }
        return result;
    }

    private static boolean isLinux() {
        boolean result = getOS().startsWith("linux");
        if (result) {
            LOGGER.warn("Hello. I'm Linux");
        }
        return result;
    }

}

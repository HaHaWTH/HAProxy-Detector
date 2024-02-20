package io.wdsj.haproxydetector.bungee;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Collection;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import com.google.common.collect.ForwardingSet;
import io.netty.channel.*;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.util.AttributeKey;
import io.wdsj.haproxydetector.HAProxyDetectorHandler;
import io.wdsj.haproxydetector.MetricsId;
import io.wdsj.haproxydetector.ProxyWhitelist;
import io.wdsj.haproxydetector.ReflectionUtil;
import net.md_5.bungee.api.config.ListenerInfo;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import org.bstats.bungeecord.Metrics;
import zone.rong.imaginebreaker.ImagineBreaker;

public final class BungeeMain extends Plugin implements Listener {
    static Logger logger;
    static Predicate<ListenerInfo> proxyProtocolChecker;
    static AttributeKey<ListenerInfo> listenerAttr;
    static Field initMapField;
    ChannelInitializer<Channel> serverChild;
    Set<ChannelHandlerContext> originalInitMap;

    @Override
    public void onLoad() {
        logger = getLogger();
        logger.info("Starting ImagineBreaker...");
        try {
            ImagineBreaker.openBootModules();
            ImagineBreaker.wipeFieldFilters();
            ImagineBreaker.wipeMethodFilters();
        } catch (Exception ignored) {
        }

        try {
            MethodHandle handle = MethodHandles.lookup().findVirtual(ListenerInfo.class, "isProxyProtocol",
                    MethodType.methodType(boolean.class));
            proxyProtocolChecker = (info) -> {
                try {
                    return (boolean) handle.invokeExact(info);
                } catch (Throwable e) {
                    ReflectionUtil.sneakyThrow(e);
                    return false;
                }
            };
        } catch (NoSuchMethodException ignored) {
            proxyProtocolChecker = null;
        } catch (ReflectiveOperationException e) {
            ReflectionUtil.sneakyThrow(e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onEnable() {
        try {
            Path path = this.getDataFolder().toPath().resolve("whitelist.conf");
            ProxyWhitelist whitelist = ProxyWhitelist.loadOrDefault(path).orElse(null);
            if (whitelist == null) {
                logger.warning("!!! ==============================");
                logger.warning("!!! Proxy whitelist is disabled in the config.");
                logger.warning("!!! This is EXTREMELY DANGEROUS, don't do this in production!");
                logger.warning("!!! ==============================");
            } else if (whitelist.size() == 0) {
                logger.warning("Proxy whitelist is empty. This will disallow all proxies!");
            }
            ProxyWhitelist.whitelist = whitelist;
        } catch (IOException e) {
            throw new RuntimeException("failed to load proxy whitelist", e);
        }

        try {
            Class<?> pipelineUtilsClass = Class.forName("net.md_5.bungee.netty.PipelineUtils", true,
                    Thread.currentThread().getContextClassLoader());
            listenerAttr = (AttributeKey<ListenerInfo>) pipelineUtilsClass.getField("LISTENER").get(null);

            // Here, we need to hijack the channel initializer for BC listeners.
            //
            // Originally I tried modifying the value of `PipelineUtils.SERVER_CHILD`,
            // replacing it with our own. But this approach no longer works in JDK 18.
            //
            // Instead of hijacking `SERVER_CHILD` itself, we're going to replace its `initMap`,
            // whose `add` method will be called internally every time before `initChannel`.
            this.serverChild = (ChannelInitializer<Channel>) pipelineUtilsClass.getField(
                    "SERVER_CHILD").get(null);
            initMapField = ChannelInitializer.class.getDeclaredField("initMap");
            initMapField.setAccessible(true);

            Set<ChannelHandlerContext> originalInitMap = (Set<ChannelHandlerContext>) initMapField.get(serverChild);
            Set<ChannelHandlerContext> myInitMap = new DetectorInitSet(originalInitMap);
            initMapField.set(serverChild, myInitMap);
        } catch (Throwable e) {
            ReflectionUtil.sneakyThrow(e);
            return;
        }

        if (proxyProtocolChecker != null) {
            if (Stream.concat(getProxy().getConfigurationAdapter().getListeners().stream(),
                    getProxy().getConfig().getListeners().stream()).noneMatch(proxyProtocolChecker)) {
                logger.warning("Proxy protocol is disabled, the plugin may not work correctly!");
            }
        }

        try {
            Metrics metrics = new Metrics(this, 21070);
            metrics.addCustomChart(MetricsId.createWhitelistCountChart());
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Failed to start metrics", t);
        }
    }

    @Override
    public void onDisable() {
        if (initMapField != null && serverChild != null && originalInitMap != null) {
            try {
                initMapField.set(serverChild, originalInitMap);
                originalInitMap = null;
                serverChild = null;
            } catch (Throwable ignored) {
            }
        }
    }

    static class DetectorInitSet extends ForwardingSet<ChannelHandlerContext> {
        private final Set<ChannelHandlerContext> delegate;

        public DetectorInitSet(Set<ChannelHandlerContext> delegate) {
            this.delegate = delegate;
        }

        @Override
        protected Set<ChannelHandlerContext> delegate() {
            return this.delegate;
        }

        @Override
        public boolean add(ChannelHandlerContext ctx) {
            if (super.add(ctx)) {
                // Delay our logic until after the original one was executed
                final Channel channel = ctx.channel();
                ctx.executor().execute(() -> initChannel(channel));
                return true;
            } else {
                return false;
            }
        }

        @Override
        public boolean addAll(Collection<? extends ChannelHandlerContext> collection) {
            return standardAddAll(collection);
        }

        private void initChannel(Channel ch) {
            if (proxyProtocolChecker != null && !proxyProtocolChecker.test(ch.attr(listenerAttr).get())) {
                return; // only proceed if listener has proxy protocol enabled
            }

            ChannelPipeline pipeline = ch.pipeline();
            if (!ch.isOpen() || pipeline.get("haproxy-detector") != null)
                return;

            HAProxyDetectorHandler detectorHandler = new HAProxyDetectorHandler(logger, null);
            ChannelHandler oldHandler;
            if ((oldHandler = pipeline.get("haproxy-decoder")) != null
                    || (oldHandler = pipeline.get(HAProxyMessageDecoder.class)) != null) {
                pipeline.replace(oldHandler, "haproxy-detector", detectorHandler);
            } else {
                throw new NoSuchElementException("HAProxy support is not enabled");
            }
        }
    }
}

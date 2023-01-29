package net.minecraft.server;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.googlecode.concurentlocks.ReentrantReadWriteUpdateLock;

import io.akarin.api.internal.utils.CheckedConcurrentLinkedQueue;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalEventLoopGroup;
import io.netty.channel.local.LocalServerChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.timeout.TimeoutException;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.net.SocketAddress;
import java.util.Queue;
import javax.annotation.Nullable;
import javax.crypto.SecretKey;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.Validate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

/**
 * Akarin Changes Note
 * 2) Expose private members (nsc)
 * 3) Changes lock type to updatable lock (compatibility)
 * 4) Removes unneed array creation (performance)
 */
public class NetworkManager extends SimpleChannelInboundHandler<Packet<?>> {

    private static final Logger g = LogManager.getLogger();
    public static final Marker a = MarkerManager.getMarker("NETWORK");
    public static final Marker b = MarkerManager.getMarker("NETWORK_PACKETS", NetworkManager.a);
    public static final AttributeKey<EnumProtocol> c = AttributeKey.valueOf("protocol");
    public static final LazyInitVar<NioEventLoopGroup> d = new LazyInitVar() {
        protected NioEventLoopGroup a() {
            return new NioEventLoopGroup(0, (new ThreadFactoryBuilder()).setNameFormat("Netty Client IO #%d").setDaemon(true).build());
        }

        @Override
        protected Object init() {
            return this.a();
        }
    };
    public static final LazyInitVar<EpollEventLoopGroup> e = new LazyInitVar() {
        protected EpollEventLoopGroup a() {
            return new EpollEventLoopGroup(0, (new ThreadFactoryBuilder()).setNameFormat("Netty Epoll Client IO #%d").setDaemon(true).build());
        }

        @Override
        protected Object init() {
            return this.a();
        }
    };
    public static final LazyInitVar<LocalEventLoopGroup> f = new LazyInitVar() {
        protected LocalEventLoopGroup a() {
            return new LocalEventLoopGroup(0, (new ThreadFactoryBuilder()).setNameFormat("Netty Local Client IO #%d").setDaemon(true).build());
        }

        @Override
        protected Object init() {
            return this.a();
        }
    };
    private final EnumProtocolDirection h;
    private final Queue<NetworkManager.QueuedPacket> i = new CheckedConcurrentLinkedQueue<NetworkManager.QueuedPacket>(); private final Queue<NetworkManager.QueuedPacket> getPacketQueue() { return this.i; } // Paper - Anti-Xray - OBFHELPER // Akarin
    private final ReentrantReadWriteUpdateLock j = new ReentrantReadWriteUpdateLock(); // Akarin - use update lock
    public Channel channel;
    // Spigot Start // PAIL
    public SocketAddress l;
    public java.util.UUID spoofedUUID;
    public com.mojang.authlib.properties.Property[] spoofedProfile;
    public boolean preparing = true;
    // Spigot End
    private PacketListener m;
    private IChatBaseComponent n;
    private boolean o;
    private boolean p;
    // Paper start - NetworkClient implementation
    public int protocolVersion;
    public java.net.InetSocketAddress virtualHost;
    private static final boolean enableExplicitFlush = Boolean.getBoolean("paper.explicit-flush");
    // Optimize network
    boolean isPending = true;
    boolean queueImmunity = false;
    EnumProtocol protocol;
    // Paper end
    
    // Tuinity start - allow controlled flushing
    volatile boolean canFlush = true;
    private final java.util.concurrent.atomic.AtomicInteger packetWrites = new java.util.concurrent.atomic.AtomicInteger();
    private int flushPacketsStart;
    private final Object flushLock = new Object();

    void disableAutomaticFlush() {
        synchronized (this.flushLock) {
            this.flushPacketsStart = this.packetWrites.get(); // must be volatile and before canFlush = false
            this.canFlush = false;
        }
    }

    void enableAutomaticFlush() {
        synchronized (this.flushLock) {
            this.canFlush = true;
            if (this.packetWrites.get() != this.flushPacketsStart) { // must be after canFlush = true
                this.flush(); // only make the flush call if we need to
            }
        }
    }

    private final void flush() {
        if (this.channel.eventLoop().inEventLoop()) {
            this.channel.flush();
        } else {
            this.channel.eventLoop().execute(() -> {
                this.channel.flush();
            });
        }
    }
    // Tuinity end - allow controlled flushing


    public NetworkManager(EnumProtocolDirection enumprotocoldirection) {
        this.h = enumprotocoldirection;
    }

    @Override
    public void channelActive(ChannelHandlerContext channelhandlercontext) throws Exception {
        super.channelActive(channelhandlercontext);
        this.channel = channelhandlercontext.channel();
        this.l = this.channel.remoteAddress();
        // Spigot Start
        this.preparing = false;
        // Spigot End

        try {
            this.setProtocol(EnumProtocol.HANDSHAKING);
        } catch (Throwable throwable) {
            NetworkManager.g.fatal(throwable);
        }

    }

    public void setProtocol(EnumProtocol enumprotocol) {
        protocol = enumprotocol; // Paper
        this.channel.attr(NetworkManager.c).set(enumprotocol);
        this.channel.config().setAutoRead(true);
        NetworkManager.g.debug("Enabled auto read");
    }

    @Override
    public void channelInactive(ChannelHandlerContext channelhandlercontext) throws Exception {
        this.close(new ChatMessage("disconnect.endOfStream", new Object[0]));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext channelhandlercontext, Throwable throwable) throws Exception {
        ChatMessage chatmessage;

        if (throwable instanceof TimeoutException) {
            chatmessage = new ChatMessage("disconnect.timeout", new Object[0]);
        } else {
            chatmessage = new ChatMessage("disconnect.genericReason", new Object[] { "Internal Exception: " + throwable});
        }

        NetworkManager.g.debug(chatmessage.toPlainText(), throwable);
        this.close(chatmessage);
        if (MinecraftServer.getServer().isDebugging()) throwable.printStackTrace(); // Spigot
    }

    protected void a(ChannelHandlerContext channelhandlercontext, Packet<?> packet) throws Exception {
        if (this.channel.isOpen()) {
            try {
                ((Packet) packet).a(this.m); // CraftBukkit - decompile error
            } catch (CancelledPacketHandleException cancelledpackethandleexception) {
                ;
            }
        }

    }

    public void setPacketListener(PacketListener packetlistener) {
        Validate.notNull(packetlistener, "packetListener", new Object[0]);
        NetworkManager.g.debug("Set listener of {} to {}", this, packetlistener);
        this.m = packetlistener;
    }

    // Paper start
    EntityPlayer getPlayer() {
        if (m instanceof PlayerConnection) {
            return ((PlayerConnection) m).player;
        } else {
            return null;
        }
    }
    private static class InnerUtil { // Attempt to hide these methods from ProtocolLib so it doesn't accidently pick them up.
        private static java.util.List<Packet> buildExtraPackets(Packet packet) {
            java.util.List<Packet> extra = packet.getExtraPackets();
            if (extra == null || extra.isEmpty()) {
                return null;
            }
            java.util.List<Packet> ret = new java.util.ArrayList<>(1 + extra.size());
            buildExtraPackets0(extra, ret);
            return ret;
        }
        private static void buildExtraPackets0(java.util.List<Packet> extraPackets, java.util.List<Packet> into) {
            for (Packet extra : extraPackets) {
                into.add(extra);
                java.util.List<Packet> extraExtra = extra.getExtraPackets();
                if (extraExtra != null && !extraExtra.isEmpty()) {
                    buildExtraPackets0(extraExtra, into);
                }
            }
        }
        // Paper start
        private static boolean canSendImmediate(NetworkManager networkManager, Packet<?> packet) {
            return networkManager.isPending || networkManager.protocol != EnumProtocol.PLAY ||
                    packet instanceof PacketPlayOutKeepAlive ||
                    packet instanceof PacketPlayOutChat ||
                    packet instanceof PacketPlayOutTabComplete ||
                    packet instanceof PacketPlayOutTitle ||
                    packet instanceof PacketPlayOutBoss;
        }
        // Paper end
    }
    // Paper end
 
    public void sendPacket(Packet<?> packet) {
        this.sendPacket(packet, (GenericFutureListener) null);
    }

    public void sendPacket(Packet<?> packet, @Nullable GenericFutureListener<? extends Future<? super Void>> genericfuturelistener) {
        // Paper start - handle oversized packets better
        boolean connected = this.isConnected();
        if (!connected && !preparing) {
            return; // Do nothing
        }
        packet.onPacketDispatch(getPlayer());
        if (connected && (InnerUtil.canSendImmediate(this, packet) || (
                MCUtil.isMainThread() && packet.isReady() && this.i.isEmpty() &&
                        (packet.getExtraPackets() == null || packet.getExtraPackets().isEmpty())
        ))) {
            this.writePacket(packet, genericfuturelistener, null); // Tuinity
            return;
        }
        // write the packets to the queue, then flush - antixray hooks there already
        java.util.List<Packet> extraPackets = InnerUtil.buildExtraPackets(packet);
        boolean hasExtraPackets = extraPackets != null && !extraPackets.isEmpty();
        if (!hasExtraPackets) {
            this.i.add(new NetworkManager.QueuedPacket(packet, genericfuturelistener));
        } else {
            java.util.List<NetworkManager.QueuedPacket> packets = new java.util.ArrayList<>(1 + extraPackets.size());
            packets.add(new NetworkManager.QueuedPacket(packet, null)); // delay the future listener until the end of the extra packets

            for (int i = 0, len = extraPackets.size(); i < len;) {
                Packet extra = extraPackets.get(i);
                boolean end = ++i == len;
                packets.add(new NetworkManager.QueuedPacket(extra, end ? genericfuturelistener : null)); // append listener to the end
            }
            this.i.addAll(packets); // atomic
        }
        this.sendPacketQueue();
        // Paper end 

    }

    private void dispatchPacket(final Packet<?> packet, @Nullable final GenericFutureListener<? extends Future<? super Void>> genericFutureListener) { this.a(packet, genericFutureListener); } // Paper - OBFHELPER
    private void a(final Packet<?> packet, @Nullable final GenericFutureListener<? extends Future<? super Void>> genericfuturelistener) {
        // Tuinity start - add flush parameter
        this.writePacket(packet, genericfuturelistener, Boolean.TRUE);
    }
    private void writePacket(Packet<?> packet, @Nullable GenericFutureListener<? extends Future<? super Void>> genericfuturelistener, Boolean flushConditional) {
        this.packetWrites.getAndIncrement(); // must be befeore using canFlush
        boolean effectiveFlush = flushConditional == null ? this.canFlush : flushConditional.booleanValue();
        final boolean flush = effectiveFlush || packet instanceof PacketPlayOutKeepAlive || packet instanceof PacketPlayOutKickDisconnect; // no delay for certain packets
        final EnumProtocol enumprotocol = EnumProtocol.a(packet);
        final EnumProtocol enumprotocol1 = this.channel.attr(NetworkManager.c).get();

        if (enumprotocol1 != enumprotocol) {
            NetworkManager.g.debug("Disabled auto read");
            this.channel.config().setAutoRead(false);
        }

        EntityPlayer player = getPlayer(); // Paper
        if (this.channel.eventLoop().inEventLoop()) {
            if (enumprotocol != enumprotocol1) {
                this.setProtocol(enumprotocol);
            }

            // Paper start
            if (!isConnected()) {
                packet.onPacketDispatchFinish(player, null);
                return;
            }
try {
                // Paper end
                ChannelFuture channelfuture = (flush) ? this.channel.writeAndFlush(packet) : this.channel.write(packet); // Tuinity - add flush parameter

                if (genericfuturelistener != null) {
                    channelfuture.addListener(genericfuturelistener);
                }
                // Paper start
                if (packet.hasFinishListener()) {
                    channelfuture.addListener((ChannelFutureListener) channelFuture -> packet.onPacketDispatchFinish(player, channelFuture));
                }
                // Paper end

                channelfuture.addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
                // Paper start
            } catch (Exception e) {
                g.error("NetworkException: " + player, e);
                close(new ChatMessage("disconnect.genericReason", "Internal Exception: " + e.getMessage()));;
                packet.onPacketDispatchFinish(player, null);
            }
            // Paper end
        } else {
            this.channel.eventLoop().execute((Runnable) () -> {
                if (enumprotocol != enumprotocol1) {
                    NetworkManager.this.setProtocol(enumprotocol);
                }
                // Paper start
                if (!isConnected()) {
                    packet.onPacketDispatchFinish(player, null);
                    return;
                }
                try {
                    // Paper end
                    ChannelFuture channelfuture = (flush) ? this.channel.writeAndFlush(packet) : this.channel.write(packet); // Tuinity - add flush parameter

                    if (genericfuturelistener != null) {
                        channelfuture.addListener(genericfuturelistener);
                    }
                    // Paper start
                    if (packet.hasFinishListener()) {
                        channelfuture.addListener((ChannelFutureListener) channelFuture -> packet.onPacketDispatchFinish(player, channelFuture));
                    }
                    // Paper end

                    channelfuture.addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
                    // Paper start
                } catch (Exception e) {
                    g.error("NetworkException: " + player, e);
                    close(new ChatMessage("disconnect.genericReason", "Internal Exception: " + e.getMessage()));;
                    packet.onPacketDispatchFinish(player, null);
                }
                // Paper end
            });
        }
         // Paper start
         java.util.List<Packet> extraPackets = packet.getExtraPackets();
         if (extraPackets != null && !extraPackets.isEmpty()) {
             for (Packet extraPacket : extraPackets) {
                this.dispatchPacket(extraPacket, genericfuturelistener);
             }
         }
         // Paper end

    }

    // Paper start - rewrite this to be safer if ran off main thread
    private boolean sendPacketQueue() { return this.m(); } // OBFHELPER // void -> boolean
    private boolean m() { // void -> boolean
        if (!isConnected() || this.i.isEmpty()) {
            return true;
        }
        if (MCUtil.isMainThread()) {
            return processQueue();
        } else if (isPending) {
            // Should only happen during login/status stages
            synchronized (this.i) {
                return this.processQueue();
            }
        }
        return false;
    }
    private boolean processQueue() {
        if (this.i.isEmpty()) return true;
        final boolean needsFlush = this.canFlush; // Tuinity - make only one flush call per sendPacketQueue() call
        boolean hasWrotePacket = false;
        // If we are on main, we are safe here in that nothing else should be processing queue off main anymore
        // But if we are not on main due to login/status, the parent is synchronized on packetQueue
        java.util.Iterator<QueuedPacket> iterator = this.i.iterator();
        while (iterator.hasNext()) {
            NetworkManager.QueuedPacket queued = iterator.next(); // poll -> peek

            // Fix NPE (Spigot bug caused by handleDisconnection())
            if (false && queued == null) { // Tuinity - diff on change, this logic is redundant: iterator guarantees ret of an element - on change, hook the flush logic here
                return true;
            }

            Packet<?> packet = queued.getPacket();
            if (!packet.isReady()) {
                // Tuinity start - make only one flush call per sendPacketQueue() call
                if (hasWrotePacket && (needsFlush || this.canFlush)) {
                    this.flush();
                }
                // Tuinity end - make only one flush call per sendPacketQueue() call
                return false;
            } else {
                iterator.remove();
                this.writePacket(packet, queued.getGenericFutureListener(), (!iterator.hasNext() && (needsFlush || this.canFlush)) ? Boolean.TRUE : Boolean.FALSE); // Tuinity - make only one flush call per sendPacketQueue() call
                hasWrotePacket = true; // Tuinity - make only one flush call per sendPacketQueue() call
            }

        }

        return true; // Return true if all packets were dispatched
    }
    // Paper end

    public void a() {
        this.m();
        if (this.m instanceof ITickable) {
            ((ITickable) this.m).e();
        }

        if (this.channel != null) {
            if (enableExplicitFlush) this.channel.eventLoop().execute(() -> this.channel.flush()); // Paper - we don't need to explicit flush here, but allow opt in incase issues are found to a better version
        }

    }

    public SocketAddress getSocketAddress() {
        return this.l;
    }
    
    // Paper start
    public void clearPacketQueue() {
        EntityPlayer player = getPlayer();
        i.forEach(queuedPacket -> {
            Packet<?> packet = queuedPacket.getPacket();
            if (packet.hasFinishListener()) {
                packet.onPacketDispatchFinish(player, null);
            }
        });
        i.clear();
    } // Paper end

    public void close(IChatBaseComponent ichatbasecomponent) {
        // Spigot Start
        this.preparing = false;
        clearPacketQueue(); // Paper
        // Spigot End
        if (this.channel.isOpen()) {
            this.channel.close(); // We can't wait as this may be called from an event loop.
            this.n = ichatbasecomponent;
        }

    }

    public boolean isLocal() {
        return this.channel instanceof LocalChannel || this.channel instanceof LocalServerChannel;
    }

    public void a(SecretKey secretkey) {
        this.o = true;
        this.channel.pipeline().addBefore("splitter", "decrypt", new PacketDecrypter(MinecraftEncryption.a(2, secretkey)));
        this.channel.pipeline().addBefore("prepender", "encrypt", new PacketEncrypter(MinecraftEncryption.a(1, secretkey)));
    }

    public boolean isConnected() {
        return this.channel != null && this.channel.isOpen();
    }

    public boolean h() {
        return this.channel == null;
    }

    public PacketListener i() {
        return this.m;
    }

    public IChatBaseComponent j() {
        return this.n;
    }

    public void stopReading() {
        this.channel.config().setAutoRead(false);
    }

    public void setCompressionLevel(int i) {
        if (i >= 0) {
            if (this.channel.pipeline().get("decompress") instanceof PacketDecompressor) {
                ((PacketDecompressor) this.channel.pipeline().get("decompress")).a(i);
            } else {
                this.channel.pipeline().addBefore("decoder", "decompress", new PacketDecompressor(i));
            }

            if (this.channel.pipeline().get("compress") instanceof PacketCompressor) {
                ((PacketCompressor) this.channel.pipeline().get("compress")).a(i);
            } else {
                this.channel.pipeline().addBefore("encoder", "compress", new PacketCompressor(i));
            }
        } else {
            if (this.channel.pipeline().get("decompress") instanceof PacketDecompressor) {
                this.channel.pipeline().remove("decompress");
            }

            if (this.channel.pipeline().get("compress") instanceof PacketCompressor) {
                this.channel.pipeline().remove("compress");
            }
        }

    }

    public void handleDisconnection() {
        if (this.channel != null && !this.channel.isOpen()) {
            if (this.p) {
                //NetworkManager.LOGGER.warn("handleDisconnection() called twice"); // Paper - Do not log useless message
            } else {
                this.p = true;
                if (this.j() != null) {
                    this.i().a(this.j());
                } else if (this.i() != null) {
                    this.i().a(new ChatMessage("multiplayer.disconnect.generic", new Object[0]));
                }
                clearPacketQueue(); // Paper
            }

        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext channelhandlercontext, Packet object) throws Exception { // CraftBukkit - fix decompile error
        // FlamePaper - Check if channel is opened before reading packet
        if (isConnected()) {
            this.a(channelhandlercontext, object);
        }
    }

    public static class QueuedPacket { // Akarin - default -> public

        private final Packet<?> a; public final Packet<?> getPacket() { return this.a; } // Paper - Anti-Xray - OBFHELPER // Akarin - private -> public
        private final GenericFutureListener<? extends Future<? super Void>> b;
        public final GenericFutureListener<? extends Future<? super Void>> getGenericFutureListener() { return this.b; } // Paper - OBFHELPER
        public QueuedPacket(Packet<?> packet, GenericFutureListener<? extends Future<? super Void>> agenericfuturelistener) {
            this.a = packet;
            this.b = agenericfuturelistener;
        }
    }

    // Spigot Start
    public SocketAddress getRawAddress()
    {
        return this.channel.remoteAddress();
    }
    // Spigot End
}

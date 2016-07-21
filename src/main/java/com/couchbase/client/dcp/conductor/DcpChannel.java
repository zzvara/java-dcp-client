/*
 * Copyright (c) 2016 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.couchbase.client.dcp.conductor;

import com.couchbase.client.core.logging.CouchbaseLogger;
import com.couchbase.client.core.logging.CouchbaseLoggerFactory;
import com.couchbase.client.dcp.config.ClientEnvironment;
import com.couchbase.client.dcp.message.*;
import com.couchbase.client.dcp.transport.netty.ChannelUtils;
import com.couchbase.client.dcp.transport.netty.DcpPipeline;
import com.couchbase.client.deps.io.netty.bootstrap.Bootstrap;
import com.couchbase.client.deps.io.netty.buffer.ByteBuf;
import com.couchbase.client.deps.io.netty.buffer.Unpooled;
import com.couchbase.client.deps.io.netty.channel.Channel;
import com.couchbase.client.deps.io.netty.channel.ChannelFuture;
import com.couchbase.client.deps.io.netty.channel.ChannelPromise;
import com.couchbase.client.deps.io.netty.util.concurrent.GenericFutureListener;
import rx.Completable;
import rx.Subscriber;
import rx.functions.Func1;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Logical representation of a DCP cluster connection.
 *
 * The equals and hashcode are based on the {@link InetAddress}.
 */
public class DcpChannel {

    private static final AtomicInteger OPAQUE = new AtomicInteger(0);

    private static final CouchbaseLogger LOGGER = CouchbaseLoggerFactory.getInstance(DcpChannel.class);

    private final ClientEnvironment env;
    private final InetAddress inetAddress;
    private final Subject<ByteBuf, ByteBuf> controlSubject;
    private final Map<Integer, ChannelPromise> outstandingResponses;
    private final Map<Integer, Short> outstandingVbucketInfos;
    private volatile Channel channel;
    private final AtomicIntegerArray openStreams;

    public DcpChannel(InetAddress inetAddress, final ClientEnvironment env) {
        this.inetAddress = inetAddress;
        this.env = env;
        this.outstandingResponses = new ConcurrentHashMap<Integer, ChannelPromise>();
        this.outstandingVbucketInfos = new ConcurrentHashMap<Integer, Short>();
        this.controlSubject = PublishSubject.<ByteBuf>create().toSerialized();
        this.openStreams = new AtomicIntegerArray(1024);

        this.controlSubject
            .filter(new Func1<ByteBuf, Boolean>() {
                @Override
                public Boolean call(ByteBuf buf) {
                    if (DcpOpenStreamResponse.is(buf)) {
                        try {
                            ChannelPromise promise = outstandingResponses.remove(MessageUtil.getOpaque(buf));
                            short vbid = outstandingVbucketInfos.remove(MessageUtil.getOpaque(buf));
                            short status = MessageUtil.getStatus(buf);
                            switch (status) {
                                case 0x00:
                                    promise.setSuccess();
                                    // create a failover log message and emit
                                    ByteBuf flog = Unpooled.buffer();
                                    DcpFailoverLogResponse.init(flog);
                                    DcpFailoverLogResponse.vbucket(flog, DcpOpenStreamResponse.vbucket(buf));
                                    MessageUtil.setContent(MessageUtil.getContent(buf).copy().writeShort(vbid), flog);
                                    env.controlEventHandler().onEvent(flog);
                                    break;
                                case 0x23:
                                    promise.setSuccess();
                                    // create a rollback message and emit
                                    ByteBuf rb = Unpooled.buffer();
                                    RollbackMessage.init(rb, vbid, MessageUtil.getContent(buf).getLong(0));
                                    env.controlEventHandler().onEvent(rb);
                                    break;
                                default:
                                    promise.setFailure(new IllegalStateException("Unhandled Status: " + status));
                            }
                            return false;
                        } finally {
                            buf.release();
                        }
                    } else if (DcpFailoverLogResponse.is(buf)) {
                        try {
                            ChannelPromise promise = outstandingResponses.remove(MessageUtil.getOpaque(buf));
                            short vbid = outstandingVbucketInfos.remove(MessageUtil.getOpaque(buf));
                            promise.setSuccess();

                            ByteBuf flog = Unpooled.buffer();
                            DcpFailoverLogResponse.init(flog);
                            DcpFailoverLogResponse.vbucket(flog, DcpOpenStreamResponse.vbucket(buf));
                            MessageUtil.setContent(MessageUtil.getContent(buf).copy().writeShort(vbid), flog);
                            env.controlEventHandler().onEvent(flog);
                            return false;
                        } finally {
                            buf.release();
                        }
                    } else if (DcpStreamEndMessage.is(buf)) {
                        try {
                            int flag = MessageUtil.getExtras(buf).readInt();
                            short vbid = DcpStreamEndMessage.vbucket(buf);
                            LOGGER.debug("Server closed Stream on vbid {} with flag {}", vbid, flag);
                            openStreams.set(vbid, 0);
                            return false;
                        } finally {
                            buf.release();
                        }
                    }
                    return true;
                }
            })
            .subscribe(new Subscriber<ByteBuf>() {
                @Override
                public void onCompleted() {
                    // Ignoring on purpose.
                }

                @Override
                public void onError(Throwable e) {
                    // Ignoring on purpose.
                }

                @Override
                public void onNext(ByteBuf buf) {
                    env.controlEventHandler().onEvent(buf);
                }
            });
    }

    public void connect() {
        final Bootstrap bootstrap = new Bootstrap()
            .remoteAddress(inetAddress, 11210)
            .channel(ChannelUtils.channelForEventLoopGroup(env.eventLoopGroup()))
            .handler(new DcpPipeline(env, controlSubject))
            .group(env.eventLoopGroup());

        bootstrap.connect().addListener(new GenericFutureListener<ChannelFuture>() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    channel = future.channel();
                    LOGGER.info("Connected to Node {}", channel.remoteAddress());
                } else {
                    LOGGER.warn("IMPLEMENT ME!!! (retry on failure until removed)");
                }
            }
        });
    }

    public void disconnect() {
        if (channel != null) {
            channel.close().addListener(new GenericFutureListener<ChannelFuture>() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (!future.isSuccess()) {
                        LOGGER.debug("Error during channel close.", future.cause());
                    }
                }
            });
        }
    }

    public InetAddress hostname() {
        return inetAddress;
    }


    public Completable openStream(final short vbid, final long vbuuid, final long startSeqno, final long endSeqno,
                                  final long snapshotStartSeqno, final long snapshotEndSeqno) {
        return Completable.create(new Completable.CompletableOnSubscribe() {
            @Override
            public void call(final Completable.CompletableSubscriber subscriber) {
                LOGGER.debug("Opening Stream against {} with vbid: {}, vbuuid: {}, startSeqno: {}, " +
                    "endSeqno: {},  snapshotStartSeqno: {}, snapshotEndSeqno: {}",
                    channel.remoteAddress(), vbid, vbuuid, startSeqno, endSeqno, snapshotStartSeqno, snapshotEndSeqno);

                int opaque = OPAQUE.incrementAndGet();
                ChannelPromise promise = channel.newPromise();

                ByteBuf buffer = Unpooled.buffer();
                DcpOpenStreamRequest.init(buffer, vbid);
                DcpOpenStreamRequest.opaque(buffer, opaque);
                DcpOpenStreamRequest.vbuuid(buffer, vbuuid);
                DcpOpenStreamRequest.startSeqno(buffer, startSeqno);
                DcpOpenStreamRequest.endSeqno(buffer, endSeqno);
                DcpOpenStreamRequest.snapshotStartSeqno(buffer, snapshotStartSeqno);
                DcpOpenStreamRequest.snapshotEndSeqno(buffer, snapshotEndSeqno);

                outstandingResponses.put(opaque, promise);
                outstandingVbucketInfos.put(opaque, vbid);
                channel.writeAndFlush(buffer);

                promise.addListener(new GenericFutureListener<ChannelFuture>() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if (future.isSuccess()) {
                            LOGGER.debug("Opened Stream against {} with vbid: {}", channel.remoteAddress(), vbid);
                            openStreams.set(vbid, 1);
                            subscriber.onCompleted();
                        } else {
                            LOGGER.debug("Failed open Stream against {} with vbid: {}", channel.remoteAddress(), vbid);
                            openStreams.set(vbid, 0);
                            subscriber.onError(future.cause());
                        }
                    }
                });
            }
        });
    }

    public Completable getFailoverLog(final short vbid) {
        return Completable.create(new Completable.CompletableOnSubscribe() {
            @Override
            public void call(final Completable.CompletableSubscriber subscriber) {
                int opaque = OPAQUE.incrementAndGet();
                ChannelPromise promise = channel.newPromise();

                ByteBuf buffer = Unpooled.buffer();
                DcpFailoverLogRequest.init(buffer);
                DcpFailoverLogRequest.opaque(buffer, opaque);
                DcpFailoverLogRequest.vbucket(buffer, vbid);

                outstandingResponses.put(opaque, promise);
                outstandingVbucketInfos.put(opaque, vbid);
                channel.writeAndFlush(buffer);

                promise.addListener(new GenericFutureListener<ChannelFuture>() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if (future.isSuccess()) {
                            LOGGER.debug("Asked for failover log on {} for vbid: {}", channel.remoteAddress(), vbid);
                            subscriber.onCompleted();
                        } else {
                            LOGGER.debug("Failed to ask for failover log on {} for vbid: {}", channel.remoteAddress(), vbid);
                            subscriber.onError(future.cause());
                        }
                    }
                });
            }
        });
    }

    public Completable closeStream(short vbid) {

        // close stream
        // and set openStreams.set(vbid, 0);

        return null;
    }

    public boolean streamIsOpen(short vbid) {
        return openStreams.get(vbid) == 1;
    }

    @Override
    public boolean equals(Object o) {
        return inetAddress.equals(o);
    }

    @Override
    public int hashCode() {
        return inetAddress.hashCode();
    }
}
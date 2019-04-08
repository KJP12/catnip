/*
 * Copyright (c) 2019 amy, All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holder nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.mewna.catnip.shard.buffer;

import com.google.common.collect.ImmutableSet;
import com.mewna.catnip.util.JsonUtil;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.beans.ConstructorProperties;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import static com.mewna.catnip.shard.CatnipShard.LARGE_THRESHOLD;
import static com.mewna.catnip.shard.DiscordEvent.Raw;

/**
 * An implementation of {@link EventBuffer} used for the case of caching all
 * guilds sent in the {@code READY} payload, as well as for caching data as it
 * comes over the websocket connection.
 *
 * @author amy
 * @since 9/9/18.
 */
@SuppressWarnings("unused")
public class CachingBuffer extends AbstractBuffer {
    private static final Set<String> CACHE_EVENTS = ImmutableSet.of(
            // Lifecycle
            Raw.READY,
            // Channels
            Raw.CHANNEL_CREATE, Raw.CHANNEL_UPDATE, Raw.CHANNEL_DELETE,
            // Guilds
            Raw.GUILD_CREATE, Raw.GUILD_UPDATE, Raw.GUILD_DELETE,
            // Roles
            Raw.GUILD_ROLE_CREATE, Raw.GUILD_ROLE_UPDATE, Raw.GUILD_ROLE_DELETE,
            // Emoji
            Raw.GUILD_EMOJIS_UPDATE,
            // Members
            Raw.GUILD_MEMBER_ADD, Raw.GUILD_MEMBER_REMOVE, Raw.GUILD_MEMBER_UPDATE,
            // Member chunking
            Raw.GUILD_MEMBERS_CHUNK,
            // Users
            Raw.USER_UPDATE, Raw.PRESENCE_UPDATE,
            // Voice
            Raw.VOICE_STATE_UPDATE
    );
    
    private static final Set<String> DELETE_EVENTS = ImmutableSet.of(
            // Channels
            Raw.CHANNEL_DELETE,
            // Guilds
            Raw.GUILD_DELETE,
            // Roles
            Raw.GUILD_ROLE_DELETE,
            // Members
            Raw.GUILD_MEMBER_REMOVE
    );
    
    private final Map<Integer, BufferState> buffers = new ConcurrentHashMap<>();
    
    @Override
    public void buffer(final JsonObject event) {
        final JsonObject shardData = event.getJsonObject("shard");
        final int id = shardData.getInteger("id");
        final String type = event.getString("t");
        
        final JsonObject d = event.getJsonObject("d");
        final BufferState bufferState = buffers.get(id);
        switch(type) {
            case Raw.READY: {
                handleReady(id, event);
                // In theory, we shouldn't need `bufferState != null` checks
                // beyond this point. The default vert.x event bus is
                // single-threaded, and #handleReady will already insert a
                // BufferState into the mappings for us.
                break;
            }
            case Raw.GUILD_CREATE: {
                handleGuildCreate(bufferState, event);
                break;
            }
            case Raw.GUILD_MEMBERS_CHUNK: {
                handleGuildMemberChunk(bufferState, event);
                break;
            }
            default: {
                // Buffer and replay later
                handleEvent(id, bufferState, event);
                break;
            }
        }
    }
    
    private void handleReady(final int shardId, final JsonObject event) {
        final JsonObject payloadData = event.getJsonObject("d");
        final String eventType = event.getString("t");
        final Set<String> guilds = JsonUtil.toMutableSet(payloadData.getJsonArray("guilds"), g -> g.getString("id"));
        buffers.put(shardId, new BufferState(shardId, guilds));
        catnip().logAdapter().debug("Prepared new BufferState for shard {} with {} guilds.", shardId, guilds.size());
        // READY is also a cache event, as it does come with
        // information about the current user
        maybeCache(eventType, shardId, payloadData).setHandler(_res -> emitter().emit(event));
    }
    
    private void handleGuildCreate(final BufferState bufferState, final JsonObject event) {
        final int shardId = bufferState.id();
        final JsonObject payloadData = event.getJsonObject("d");
        final String guild = payloadData.getString("id");
        // Make sure to cache guild
        // This will always succeed unless something goes horribly wrong
        maybeCache(Raw.GUILD_CREATE, shardId, payloadData).setHandler(_res -> {
            // Add the guild to be awaited so that we can buffer members
            bufferState.awaitGuild(guild);
            
            // Trigger member chunking if needed
            final Integer memberCount = payloadData.getInteger("member_count");
            if(catnip().chunkMembers() && memberCount > LARGE_THRESHOLD) {
                // If we're chunking members, calculate how many chunks we have to await
                int chunks = memberCount / 1000;
                if(memberCount % 1000 != 0) {
                    // Not a perfect 1k, add a chunk to make up for how math works
                    chunks += 1;
                }
                bufferState.initialGuildChunkCount(guild, chunks, event);
                // Actually send the chunking request
                catnip().chunkMembers(guild);
                // I hate this
                final int finalChunks = chunks;
                catnip().vertx().setTimer(catnip().memberChunkTimeout(), __ -> {
                    if(bufferState.guildChunkCount().containsKey(guild)) {
                        final Counter counter = bufferState.guildChunkCount().get(guild);
                        if(counter != null) {
                            // Yeah, I know it shouldn't be an issue, but
                            // honestly at this point I don't really trust this
                            // class to work right :I
                            // Rewrite when
                            if(counter.count != 0) {
                                catnip().logAdapter()
                                        .warn("Didn't recv. member chunks for guild {} in time, re-requesting...",
                                                guild);
                                // Reset chunk count
                                bufferState.initialGuildChunkCount(guild, finalChunks, event);
                                catnip().chunkMembers(guild);
                                catnip().vertx().setTimer(catnip().memberChunkTimeout(), ___ -> {
                                    if(bufferState.guildChunkCount().containsKey(guild)) {
                                        final Counter counterTwo = bufferState.guildChunkCount().get(guild);
                                        if(counterTwo != null) {
                                            catnip().logAdapter()
                                                    .warn("Didn't recv. member chunks for guild {} after {}ms even " +
                                                                    "after retrying (missing {} chunks)! Please report this!",
                                                            guild, finalChunks - counterTwo.count(),
                                                            catnip().memberChunkTimeout());
                                        }
                                    }
                                });
                            }
                        }
                    }
                });
            } else {
                // TODO(#255): need to properly defer the emit until we recv. the optionally-created role
                emitter().emit(event);
                bufferState.receiveGuild(guild);
                bufferState.replayGuild(guild);
                // Replay all buffered events once we run out
                if(bufferState.awaitedGuilds().isEmpty()) {
                    bufferState.replay();
                }
            }
        });
    }
    
    private void handleGuildMemberChunk(final BufferState bufferState, final JsonObject event) {
        final JsonObject payloadData = event.getJsonObject("d");
        final String eventType = event.getString("t");
        
        if(catnip().chunkMembers()) {
            final String guild = payloadData.getString("guild_id");
            cacheAndDispatch(eventType, bufferState.id(), event);
            bufferState.acceptChunk(guild);
            if(bufferState.doneChunking(guild)) {
                emitter().emit(bufferState.guildCreate(guild));
                bufferState.receiveGuild(guild);
                bufferState.replayGuild(guild);
                // Replay all buffered events once we run out
                if(bufferState.awaitedGuilds().isEmpty()) {
                    bufferState.replay();
                }
            }
        }
    }
    
    private void handleEvent(final int id, final BufferState bufferState, final JsonObject event) {
        final JsonObject payloadData = event.getJsonObject("d");
        final String eventType = event.getString("t");
        
        final String guildId = payloadData.getString("guild_id", null);
        if(guildId != null) {
            if(bufferState.awaitedGuilds().contains(guildId)) {
                // If we have a guild id, and we have a guild being awaited,
                // buffer the event
                bufferState.receiveGuildEvent(guildId, event);
            } else {
                // If we're not awaiting the guild, it means that we're done
                // buffering events for the guild - ie. all member chunks have
                // been received - and so we can emit
                cacheAndDispatch(eventType, id, event);
            }
        } else {
            // Emit if the payload has no guild id
            cacheAndDispatch(eventType, id, event);
        }
    }
    
    private void cacheAndDispatch(final String type, final int id, final JsonObject event) {
        // We *always* emit the event BEFORE updating the cache, so that you
        // can ex. compare with the old cache first
        // TODO: Cache updates are async - this is likely a race condition
        // Is there any reasonable way to fix this?
        final JsonObject d = event.getJsonObject("d");
        emitter().emit(event);
        maybeCache(type, id, d);
    }
    
    private Future<Void> maybeCache(final String eventType, final int shardId, final JsonObject data) {
        if(CACHE_EVENTS.contains(eventType)) {
            try {
                return catnip().cacheWorker().updateCache(eventType, shardId, data);
            } catch(final Exception e) {
                catnip().logAdapter().warn("Got error updating cache for payload {}", eventType, e);
                catnip().logAdapter().warn("Payload: {}", data.encodePrettily());
                return Future.failedFuture(e);
            }
        } else {
            return Future.succeededFuture();
        }
    }
    
    private final class BufferState {
        private final int id;
        private final Set<String> awaitedGuilds;
        private final Map<String, Deque<JsonObject>> guildBuffers = new ConcurrentHashMap<>();
        private final Map<String, Counter> guildChunkCount = new ConcurrentHashMap<>();
        private final Deque<JsonObject> buffer = new ConcurrentLinkedDeque<>();
    
        @ConstructorProperties({"id", "awaitedGuilds"})
        private BufferState(final int id, final Set<String> awaitedGuilds) {
            this.id = id;
            this.awaitedGuilds = awaitedGuilds;
        }
    
        void awaitGuild(final String id) {
            awaitedGuilds.add(id);
        }
        
        void receiveGuild(final String id) {
            awaitedGuilds.remove(id);
        }
        
        void receiveGuildEvent(final String id, final JsonObject event) {
            final Deque<JsonObject> queue = guildBuffers.computeIfAbsent(id, __ -> new ConcurrentLinkedDeque<>());
            queue.addLast(event);
        }
        
        void buffer(final JsonObject event) {
            buffer.addLast(event);
        }
        
        void replayGuild(final String id) {
            if(guildBuffers.containsKey(id)) {
                final Deque<JsonObject> queue = guildBuffers.get(id);
                queue.forEach(emitter()::emit);
            }
        }
        
        void replay() {
            buffer.forEach(emitter()::emit);
        }
        
        void initialGuildChunkCount(final String guild, final int count, final JsonObject guildCreate) {
            guildChunkCount.put(guild, new Counter(guildCreate, count));
        }
        
        void acceptChunk(final String guild) {
            final Counter counter = guildChunkCount.get(guild);
            counter.decrement();
        }
        
        boolean doneChunking(final String guild) {
            return guildChunkCount.get(guild).count() == 0;
        }
        
        JsonObject guildCreate(final String guild) {
            return guildChunkCount.get(guild).guildCreate();
        }
    
        public int id() {
            return id;
        }
    
        public Set<String> awaitedGuilds() {
            return awaitedGuilds;
        }
    
        public Map<String, Deque<JsonObject>> guildBuffers() {
            return guildBuffers;
        }
    
        public Map<String, Counter> guildChunkCount() {
            return guildChunkCount;
        }
    
        public Deque<JsonObject> buffer() {
            return buffer;
        }
    
        public boolean equals(final Object o) {
            if(o == this) {
                return true;
            }
            if(!(o instanceof BufferState)) {
                return false;
            }
            final BufferState other = (BufferState) o;
            if(id() != other.id()) {
                return false;
            }
            final Object this$awaitedGuilds = awaitedGuilds();
            final Object other$awaitedGuilds = other.awaitedGuilds();
            if(!Objects.equals(this$awaitedGuilds, other$awaitedGuilds)) {
                return false;
            }
            final Object this$guildBuffers = guildBuffers();
            final Object other$guildBuffers = other.guildBuffers();
            if(!Objects.equals(this$guildBuffers, other$guildBuffers)) {
                return false;
            }
            final Object this$guildChunkCount = guildChunkCount();
            final Object other$guildChunkCount = other.guildChunkCount();
            if(!Objects.equals(this$guildChunkCount, other$guildChunkCount)) {
                return false;
            }
            final Object this$buffer = buffer();
            final Object other$buffer = other.buffer();
            return Objects.equals(this$buffer, other$buffer);
        }
    
        public int hashCode() {
            final int PRIME = 59;
            int result = 1;
            result = result * PRIME + id();
            final Object $awaitedGuilds = awaitedGuilds();
            result = result * PRIME + ($awaitedGuilds == null ? 43 : $awaitedGuilds.hashCode());
            final Object $guildBuffers = guildBuffers();
            result = result * PRIME + ($guildBuffers == null ? 43 : $guildBuffers.hashCode());
            final Object $guildChunkCount = guildChunkCount();
            result = result * PRIME + ($guildChunkCount == null ? 43 : $guildChunkCount.hashCode());
            final Object $buffer = buffer();
            result = result * PRIME + ($buffer == null ? 43 : $buffer.hashCode());
            return result;
        }
    
        public String toString() {
            return "CachingBuffer.BufferState(id=" + id() + ", awaitedGuilds=" + awaitedGuilds() + ", guildBuffers=" + guildBuffers() + ", guildChunkCount=" + guildChunkCount() + ", buffer=" + buffer() + ')';
        }
    }
    
    private final class Counter {
        private final JsonObject guildCreate;
        private int count;
    
        @ConstructorProperties({"guildCreate", "count"})
        private Counter(final JsonObject guildCreate, final int count) {
            this.guildCreate = guildCreate;
            this.count = count;
        }
    
        void decrement() {
            --count;
        }
    
        public JsonObject guildCreate() {
            return guildCreate;
        }
    
        public int count() {
            return count;
        }
    }
}

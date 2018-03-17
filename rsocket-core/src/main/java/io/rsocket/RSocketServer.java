/*
 * Copyright 2015-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.rsocket;

import static io.rsocket.Frame.Request.initialRequestN;
import static io.rsocket.frame.FrameHeaderFlyweight.FLAGS_C;
import static io.rsocket.frame.FrameHeaderFlyweight.FLAGS_M;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.rsocket.exceptions.ApplicationException;
import io.rsocket.internal.LimitableRequestPublisher;
import io.rsocket.internal.UnboundedProcessor;
import io.rsocket.util.NonBlockingHashMapLong;
import java.util.function.Consumer;
import java.util.function.Function;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.UnicastProcessor;

/** Server side RSocket. Receives {@link Frame}s from a {@link RSocketClient} */
class RSocketServer implements RSocket {

  private final DuplexConnection connection;
  private final RSocket requestHandler;
  private final Function<Frame, ? extends Payload> frameDecoder;
  private final Consumer<Throwable> errorConsumer;

  private final NonBlockingHashMapLong<Subscription> sendingSubscriptions;
  private final NonBlockingHashMapLong<UnicastProcessor<Payload>> channelProcessors;

  private final UnboundedProcessor<Frame> sendProcessor;
  private Disposable receiveDisposable;

  RSocketServer(
      DuplexConnection connection,
      RSocket requestHandler,
      Function<Frame, ? extends Payload> frameDecoder,
      Consumer<Throwable> errorConsumer) {
    this.connection = connection;
    this.requestHandler = requestHandler;
    this.frameDecoder = frameDecoder;
    this.errorConsumer = errorConsumer;
    this.sendingSubscriptions = new NonBlockingHashMapLong<>();
    this.channelProcessors = new NonBlockingHashMapLong<>();

    // DO NOT Change the order here. The Send processor must be subscribed to before receiving
    // connections
    this.sendProcessor = new UnboundedProcessor<>();

    connection
        .send(sendProcessor)
        .doFinally(this::handleSendProcessorCancel)
        .subscribe(null, this::handleSendProcessorError);

    this.receiveDisposable = connection.receive().subscribe(this::handleFrame, errorConsumer);

    this.connection
        .onClose()
        .doFinally(
            s -> {
              cleanup();
              receiveDisposable.dispose();
            })
        .subscribe(null, errorConsumer);
  }

  private void handleSendProcessorError(Throwable t) {
    for (Subscription subscription : sendingSubscriptions.values()) {
      try {
        subscription.cancel();
      } catch (Throwable e) {
        errorConsumer.accept(e);
      }
    }

    for (UnicastProcessor subscription : channelProcessors.values()) {
      try {
        subscription.cancel();
      } catch (Throwable e) {
        errorConsumer.accept(e);
      }
    }
  }

  private void handleSendProcessorCancel(SignalType t) {
    if (SignalType.ON_ERROR == t) {
      return;
    }

    for (Subscription subscription : sendingSubscriptions.values()) {
      try {
        subscription.cancel();
      } catch (Throwable e) {
        errorConsumer.accept(e);
      }
    }

    for (UnicastProcessor subscription : channelProcessors.values()) {
      try {
        subscription.cancel();
      } catch (Throwable e) {
        errorConsumer.accept(e);
      }
    }
  }

  @Override
  public Mono<Void> fireAndForget(Payload payload) {
    try {
      return requestHandler.fireAndForget(payload);
    } catch (Throwable t) {
      return Mono.error(t);
    }
  }

  @Override
  public Mono<Payload> requestResponse(Payload payload) {
    try {
      return requestHandler.requestResponse(payload);
    } catch (Throwable t) {
      return Mono.error(t);
    }
  }

  @Override
  public Flux<Payload> requestStream(Payload payload) {
    try {
      return requestHandler.requestStream(payload);
    } catch (Throwable t) {
      return Flux.error(t);
    }
  }

  @Override
  public Flux<Payload> requestChannel(Publisher<Payload> payloads) {
    try {
      return requestHandler.requestChannel(payloads);
    } catch (Throwable t) {
      return Flux.error(t);
    }
  }

  @Override
  public Mono<Void> metadataPush(Payload payload) {
    try {
      return requestHandler.metadataPush(payload);
    } catch (Throwable t) {
      return Mono.error(t);
    }
  }

  @Override
  public void dispose() {
    connection.dispose();
  }

  @Override
  public boolean isDisposed() {
    return connection.isDisposed();
  }

  @Override
  public Mono<Void> onClose() {
    return connection.onClose();
  }

  private void cleanup() {
    cleanUpSendingSubscriptions();
    cleanUpChannelProcessors();

    requestHandler.dispose();
  }

  private synchronized void cleanUpSendingSubscriptions() {
    sendingSubscriptions.values().forEach(Subscription::cancel);
    sendingSubscriptions.clear();
  }

  private synchronized void cleanUpChannelProcessors() {
    channelProcessors.values().forEach(Subscription::cancel);
    channelProcessors.clear();
  }

  private void handleFrame(Frame frame) {
    try {
      int streamId = frame.getStreamId();
      Subscriber<Payload> receiver;
      switch (frame.getType()) {
        case FIRE_AND_FORGET:
          handleFireAndForget(streamId, fireAndForget(frameDecoder.apply(frame)));
          break;
        case REQUEST_RESPONSE:
          handleRequestResponse(streamId, requestResponse(frameDecoder.apply(frame)));
          break;
        case CANCEL:
          handleCancelFrame(streamId);
          break;
        case KEEPALIVE:
          handleKeepAliveFrame(frame);
          break;
        case REQUEST_N:
          handleRequestN(streamId, frame);
          break;
        case REQUEST_STREAM:
          handleStream(streamId, requestStream(frameDecoder.apply(frame)), initialRequestN(frame));
          break;
        case REQUEST_CHANNEL:
          handleChannel(streamId, frame);
          break;
        case PAYLOAD:
          // TODO: Hook in receiving socket.
          break;
        case METADATA_PUSH:
          metadataPush(frameDecoder.apply(frame));
          break;
        case LEASE:
          // Lease must not be received here as this is the server end of the socket which sends
          // leases.
          break;
        case NEXT:
          receiver = channelProcessors.get(streamId);
          if (receiver != null) {
            receiver.onNext(frameDecoder.apply(frame));
          }
          break;
        case COMPLETE:
          receiver = channelProcessors.get(streamId);
          if (receiver != null) {
            receiver.onComplete();
          }
          break;
        case ERROR:
          receiver = channelProcessors.get(streamId);
          if (receiver != null) {
            receiver.onError(new ApplicationException(Frame.Error.message(frame)));
          }
          break;
        case NEXT_COMPLETE:
          receiver = channelProcessors.get(streamId);
          if (receiver != null) {
            receiver.onNext(frameDecoder.apply(frame));
            receiver.onComplete();
          }
          break;
        case SETUP:
          handleError(streamId, new IllegalStateException("Setup frame received post setup."));
          break;
        default:
          handleError(
              streamId,
              new IllegalStateException(
                  "ServerRSocket: Unexpected frame type: " + frame.getType()));
          break;
      }
    } finally {
      frame.release();
    }
  }

  private void handleFireAndForget(int streamId, Mono<Void> result) {
    result
        .doFinally(signalType -> sendingSubscriptions.remove(streamId))
        .subscribe(
            null,
            errorConsumer,
            null,
            subscription -> sendingSubscriptions.put(streamId, subscription));
  }

  private void handleRequestResponse(int streamId, Mono<Payload> response) {
    response
        .doOnSubscribe(subscription -> sendingSubscriptions.put(streamId, subscription))
        .map(
            payload -> {
              int flags = FLAGS_C;
              if (payload.hasMetadata()) {
                flags = Frame.setFlag(flags, FLAGS_M);
              }
              final Frame frame =
                  Frame.PayloadFrame.from(streamId, FrameType.NEXT_COMPLETE, payload, flags);
              payload.release();
              return frame;
            })
        .doFinally(signalType -> sendingSubscriptions.remove(streamId))
        .subscribe(sendProcessor::onNext, t -> handleError(streamId, t));
  }

  private void handleStream(int streamId, Flux<Payload> response, int initialRequestN) {
    response
        .map(
            payload -> {
              final Frame frame = Frame.PayloadFrame.from(streamId, FrameType.NEXT, payload);
              payload.release();
              return frame;
            })
        .transform(
            frameFlux -> {
              LimitableRequestPublisher<Frame> frames = LimitableRequestPublisher.wrap(frameFlux);
              sendingSubscriptions.put(streamId, frames);
              frames.increaseRequestLimit(initialRequestN);
              return frames;
            })
        .concatWith(Mono.just(Frame.PayloadFrame.from(streamId, FrameType.COMPLETE)))
        .doFinally(signalType -> sendingSubscriptions.remove(streamId))
        .subscribe(sendProcessor::onNext, t -> handleError(streamId, t));
  }

  private void handleChannel(int streamId, Frame firstFrame) {
    UnicastProcessor<Payload> frames = UnicastProcessor.create();
    channelProcessors.put(streamId, frames);

    Flux<Payload> payloads =
        frames
            .doOnCancel(() -> sendProcessor.onNext(Frame.Cancel.from(streamId)))
            .doOnError(t -> sendProcessor.onNext(Frame.Error.from(streamId, t)))
            .doOnRequest(l -> sendProcessor.onNext(Frame.RequestN.from(streamId, l)))
            .doFinally(signalType -> channelProcessors.remove(streamId));

    // not chained, as the payload should be enqueued in the Unicast processor before this method
    // returns
    // and any later payload can be processed
    frames.onNext(frameDecoder.apply(firstFrame));

    handleStream(streamId, requestChannel(payloads), initialRequestN(firstFrame));
  }

  private void handleKeepAliveFrame(Frame frame) {
    if (Frame.Keepalive.hasRespondFlag(frame)) {
      ByteBuf data = Unpooled.wrappedBuffer(frame.getData());
      sendProcessor.onNext(Frame.Keepalive.from(data, false));
    }
  }

  private void handleCancelFrame(int streamId) {
    Subscription subscription = sendingSubscriptions.remove(streamId);
    if (subscription != null) {
      subscription.cancel();
    }
  }

  private void handleError(int streamId, Throwable t) {
    errorConsumer.accept(t);
    sendProcessor.onNext(Frame.Error.from(streamId, t));
  }

  private void handleRequestN(int streamId, Frame frame) {
    final Subscription subscription = sendingSubscriptions.get(streamId);
    if (subscription != null) {
      int n = Frame.RequestN.requestN(frame);
      subscription.request(n >= Integer.MAX_VALUE ? Long.MAX_VALUE : n);
    }
  }
}

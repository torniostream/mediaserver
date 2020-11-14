package org.kurento.tutorial.player;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.kurento.client.*;
import org.kurento.commons.exception.KurentoException;
import org.kurento.jsonrpc.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

public class PlayerHandler extends TextWebSocketHandler {

  @Autowired
  private KurentoClient kurento;

  private final Logger log = LoggerFactory.getLogger(PlayerHandler.class);
  private final Gson gson = new GsonBuilder().create();

  private final ConcurrentHashMap<String, UserSession> users = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, StreamingRoom> rooms = new ConcurrentHashMap<>();
  
  @Override
  public void handleTextMessage(WebSocketSession session, TextMessage message) {
    JsonObject jsonMessage = gson.fromJson(message.getPayload(), JsonObject.class);
    String sessionId = session.getId();
    log.debug("Incoming message {} from sessionId {}", jsonMessage, sessionId);

    try {
      switch (jsonMessage.get("id").getAsString()) {
        case "start":
          start(session, jsonMessage);
          break;
        case "stop":
          stop(sessionId);
          break;
        case "pause":
          pause(sessionId);
          break;
        case "register":
          registerToRoom(session, jsonMessage);
          break;
        case "resume":
          resume(session);
          break;
        case "debugDot":
          debugDot(session);
          break;
        case "doSeek":
          doSeek(session, jsonMessage);
          break;
        case "getPosition":
          getPosition(session);
          break;
        case "onIceCandidate":
          onIceCandidate(sessionId, jsonMessage);
          break;
        default:
          sendError(session, "Invalid message with id " + jsonMessage.get("id").getAsString());
          break;
      }
    } catch (Throwable t) {
      log.error("Exception handling message {} in sessionId {}", jsonMessage, sessionId, t);
      sendError(session, t.getMessage());
    }
  }

  // This gets called only the first time a room is registered
  private void start(final WebSocketSession session, JsonObject jsonMessage) {
    final UserSession user = new UserSession(session);

    String videourl = jsonMessage.get("videourl").getAsString();
    
    String uuid = UUID.randomUUID().toString();
    StreamingRoom stream = new StreamingRoom(kurento, user, videourl);

    System.out.println("code uuid " + uuid);

    rooms.put(uuid, stream);

    log.info("Codice:\n{}", uuid);
    users.put(session.getId(), user);

    // 2. WebRtcEndpoint
    // ICE candidates
    user.getWebRtcEndpoint().addIceCandidateFoundListener(event -> {
      JsonObject response = new JsonObject();
      response.addProperty("id", "iceCandidate");
      response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
      try {
        synchronized (session) {
          session.sendMessage(new TextMessage(response.toString()));
        }
      } catch (IOException e) {
        log.debug(e.getMessage());
      }
    });

    // Continue the SDP Negotiation: Generate an SDP Answer
    String sdpOffer = jsonMessage.get("sdpOffer").getAsString();
    String sdpAnswer = user.getWebRtcEndpoint().processOffer(sdpOffer);

    log.info("[Handler::start] SDP Offer from browser to KMS:\n{}", sdpOffer);
    log.info("[Handler::start] SDP Answer from KMS to browser:\n{}", sdpAnswer);

    JsonObject response = new JsonObject();
    response.addProperty("id", "startResponse");
    response.addProperty("sdpAnswer", sdpAnswer);

    sendMessage(session, response.toString());

    user.getWebRtcEndpoint().addMediaStateChangedListener(event -> {

      if (event.getNewState() == MediaState.CONNECTED) {
        VideoInfo videoInfo = stream.getPlayerEndpoint().getVideoInfo();

        JsonObject response1 = new JsonObject();
        response1.addProperty("id", "videoInfo");
        response1.addProperty("isSeekable", videoInfo.getIsSeekable());
        response1.addProperty("initSeekable", videoInfo.getSeekableInit());
        response1.addProperty("endSeekable", videoInfo.getSeekableEnd());
        response1.addProperty("videoDuration", videoInfo.getDuration());
        sendMessage(session, response1.toString());
      }
    });

    user.getWebRtcEndpoint().gatherCandidates();

    // 3. PlayEndpoint
    stream.getPlayerEndpoint().addErrorListener(event -> {
      log.info("ErrorEvent: {}", event.getDescription());
      sendPlayEnd(session);
    });

    stream.getPlayerEndpoint().addEndOfStreamListener(event -> {
      log.info("EndOfStreamEvent: {}", event.getTimestamp());
      sendPlayEnd(session);
    });

    stream.getPlayerEndpoint().play();
  }
  
  private void registerToRoom(final WebSocketSession session, JsonObject jsonMessage) {
    final UserSession user = new UserSession(session);

    String room = jsonMessage.get("roomid").getAsString();

    if (!rooms.containsKey(room)) {
      sendError(session, "Error, room not found");
      return;
    }

    final StreamingRoom stream = rooms.get(room);
    stream.addUser(user);

    users.put(session.getId(), user);

    // 2. WebRtcEndpoint
    // ICE candidates
    user.getWebRtcEndpoint().addIceCandidateFoundListener(event -> {
      JsonObject response = new JsonObject();
      response.addProperty("id", "iceCandidate");
      response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
      try {
        synchronized (session) {
          session.sendMessage(new TextMessage(response.toString()));
        }
      } catch (IOException e) {
        log.debug(e.getMessage());
      }
    });

    // Continue the SDP Negotiation: Generate an SDP Answer
    String sdpOffer = jsonMessage.get("sdpOffer").getAsString();
    String sdpAnswer = user.getWebRtcEndpoint().processOffer(sdpOffer);

    log.info("[Handler::start] SDP Offer from browser to KMS:\n{}", sdpOffer);
    log.info("[Handler::start] SDP Answer from KMS to browser:\n{}", sdpAnswer);

    JsonObject response = new JsonObject();
    response.addProperty("id", "startResponse");
    response.addProperty("sdpAnswer", sdpAnswer);
    sendMessage(session, response.toString());

    user.getWebRtcEndpoint().addMediaStateChangedListener(event -> {

      if (event.getNewState() == MediaState.CONNECTED) {
        VideoInfo videoInfo = stream.getPlayerEndpoint().getVideoInfo();

        JsonObject response1 = new JsonObject();
        response1.addProperty("id", "videoInfo");
        response1.addProperty("isSeekable", videoInfo.getIsSeekable());
        response1.addProperty("initSeekable", videoInfo.getSeekableInit());
        response1.addProperty("endSeekable", videoInfo.getSeekableEnd());
        response1.addProperty("videoDuration", videoInfo.getDuration());
        sendMessage(session, response1.toString());
      }
    });

    user.getWebRtcEndpoint().gatherCandidates();

    // 3. PlayEndpoint
    stream.getPlayerEndpoint().addErrorListener(event -> {
      log.info("ErrorEvent: {}", event.getDescription());
      sendPlayEnd(session);
    });

    stream.getPlayerEndpoint().addEndOfStreamListener(event -> {
      log.info("EndOfStreamEvent: {}", event.getTimestamp());
      sendPlayEnd(session);
    });
  }

  private void pause(String sessionId) {
    UserSession user = users.get(sessionId);

    if (user != null) {
      user.getRoom().pause();
    }
  }

  private void resume(final WebSocketSession session) {
    UserSession user = users.get(session.getId());

    if (user != null) {
      VideoInfo videoInfo = user.getRoom().getPlayerEndpoint().getVideoInfo();

      JsonObject response = new JsonObject();
      response.addProperty("id", "videoInfo");
      response.addProperty("isSeekable", videoInfo.getIsSeekable());
      response.addProperty("initSeekable", videoInfo.getSeekableInit());
      response.addProperty("endSeekable", videoInfo.getSeekableEnd());
      response.addProperty("videoDuration", videoInfo.getDuration());
      sendMessage(session, response.toString());
      
      user.getRoom().resume();
    }
  }

  private void stop(String sessionId) {
    UserSession user = users.remove(sessionId);

    if (user != null) {
      user.getRoom().removeUser(user);
    }
  }

  private void debugDot(final WebSocketSession session) {
    UserSession user = users.get(session.getId());
    if (user != null) {
      System.out.println("media trans" + user.getRoom().getPlayerEndpoint().isMediaTranscoding(MediaType.VIDEO));
      System.out.println("play " + user.getWebRtcEndpoint().isMediaTranscoding(MediaType.VIDEO));
      System.out.println("pppp " + user.getHubPort().isMediaTranscoding(MediaType.VIDEO));
    }
  }
//  private void debugDot(final WebSocketSession session) {
//    UserSession user = users.get(session.getId());
//
//    if (user != null) {
//      final String pipelineDot = user.getMediaPipeline().getGstreamerDot();
//      try (PrintWriter out = new PrintWriter("player.dot")) {
//        out.println(pipelineDot);
//      } catch (IOException ex) {
//        log.error("[Handler::debugDot] Exception: {}", ex.getMessage());
//      }
//      final String playerDot = user.getPlayerEndpoint().getElementGstreamerDot();
//      try (PrintWriter out = new PrintWriter("player-decoder.dot")) {
//        out.println(playerDot);
//      } catch (IOException ex) {
//        log.error("[Handler::debugDot] Exception: {}", ex.getMessage());
//      }
//    }
//
//    ServerManager sm = kurento.getServerManager();
//    log.warn("[Handler::debugDot] CPU COUNT: {}", sm.getCpuCount());
//    log.warn("[Handler::debugDot] CPU USAGE: {}", sm.getUsedCpu(1000));
//    log.warn("[Handler::debugDot] RAM USAGE: {}", sm.getUsedMemory());
//  }

  private void doSeek(final WebSocketSession session, JsonObject jsonMessage) {
    UserSession user = users.get(session.getId());

    if (user != null) {
      try {
        user.getRoom().getPlayerEndpoint().setPosition(jsonMessage.get("position").getAsLong());
      } catch (KurentoException e) {
        log.debug("The seek cannot be performed");
        JsonObject response = new JsonObject();
        response.addProperty("id", "seek");
        response.addProperty("message", "Seek failed");
        sendMessage(session, response.toString());
      }
    }
  }

  private void getPosition(final WebSocketSession session) {
    UserSession user = users.get(session.getId());

    if (user != null) {
      long position = user.getRoom().getPlayerEndpoint().getPosition();

      JsonObject response = new JsonObject();
      response.addProperty("id", "position");
      response.addProperty("position", position);
      sendMessage(session, response.toString());
    }
  }

  private void onIceCandidate(String sessionId, JsonObject jsonMessage) {
    UserSession user = users.get(sessionId);

    if (user != null) {
      JsonObject jsonCandidate = jsonMessage.get("candidate").getAsJsonObject();
      IceCandidate candidate =
          new IceCandidate(jsonCandidate.get("candidate").getAsString(), jsonCandidate
              .get("sdpMid").getAsString(), jsonCandidate.get("sdpMLineIndex").getAsInt());
      user.getWebRtcEndpoint().addIceCandidate(candidate);
    }
  }

  public void sendPlayEnd(WebSocketSession session) {
    if (users.containsKey(session.getId())) {
      JsonObject response = new JsonObject();
      response.addProperty("id", "playEnd");
      sendMessage(session, response.toString());
    }
  }

  private void sendError(WebSocketSession session, String message) {
    //if (users.containsKey(session.getId())) {
      JsonObject response = new JsonObject();
      response.addProperty("id", "error");
      response.addProperty("message", message);
      sendMessage(session, response.toString());
    //}
  }

  private synchronized void sendMessage(WebSocketSession session, String message) {
    try {
      session.sendMessage(new TextMessage(message));
    } catch (IOException e) {
      log.error("Exception sending message", e);
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    stop(session.getId());
  }
}

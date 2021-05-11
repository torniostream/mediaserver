package org.kurento.tutorial.player;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;


import com.google.gson.JsonElement;
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
          createRoom(session, jsonMessage);
          break;
        case "stop":
          stop(sessionId);
          break;
        case "pause":
          pause(sessionId);
          break;
        case "register":
          joinRoom(session, jsonMessage);
          break;
        case "resume":
          resume(session);
          break;
        case "inhibit":
          inhibit(session, jsonMessage, true);
          break;
        case "uninhibit":
          inhibit(session, jsonMessage, false);
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

  // An admin can inhibit a user from controlling the movie: e.g. if they have been
  // repeatedly misbehaving.
  private synchronized void inhibit(final WebSocketSession session, final JsonObject jsonMessage, final Boolean inhibit) {
    UserSession user = this.users.get(session);
    if (user == null) {
      sendError(session, "You're not registered.");
      return;
    }

    JsonElement target = jsonMessage.get("target");
    if (target == null) {
      sendError(session, "You need to specify target's nickname.");
    }

    user.getRoom().setInhibitUser(user, target.getAsString(), inhibit);
  }

  // This gets called only the first time a room is registered
  // The user that calls it first is the one that becomes admin
  private synchronized void createRoom(final WebSocketSession session, JsonObject jsonMessage) {
    JsonElement usr = jsonMessage.get("user");

    if (usr == null) {
      sendError(session, "Error, you have to set a nickname before creating the room");
      return;
    }

    Gson gson = new Gson();
    final UserSession user = gson.fromJson(usr, UserSession.class);

    user.setIsAdmin(true);
    user.setInhibited(false);
    user.setWs(session);

    if (!user.validate()) {
      sendError(session, "There was an error deserializing your user information.");
      return;
    }

    String videoURL = jsonMessage.get("videourl").getAsString();

    StreamingRoom stream = new StreamingRoom(kurento, user, videoURL);

    String uuid = stream.getUUID();

    rooms.put(uuid, stream);
    users.put(session.getId(), user);

    if (jsonMessage.get("sdpOffer") == null) {
      sendError(session, "Empty sdpOffer, cannot proceed");
      return;
    }

    setupWebRTC(user, stream, jsonMessage.get("sdpOffer").getAsString());
  }
  
  private synchronized void joinRoom(final WebSocketSession session, JsonObject jsonMessage) {
    JsonElement usr = jsonMessage.get("user");

    if (usr == null) {
      sendError(session, "Error, you have to set a nickname before joining the room");
      return;
    }

    Gson gson = new Gson();
    final UserSession user = gson.fromJson(usr, UserSession.class);

    user.setIsAdmin(false);
    user.setInhibited(false);
    user.setWs(session);

    if (!user.validate()) {
      sendError(session, "There was an error deserializing your user information.");
      return;
    }

    String room = jsonMessage.get("roomid").getAsString();

    if (!rooms.containsKey(room)) {
      sendError(session, "Error, room not found");
      return;
    }
    final StreamingRoom stream = rooms.get(room);

    if (!stream.addUser(user)) {
      return;
    }

    users.put(session.getId(), user);

    if (jsonMessage.get("sdpOffer") == null) {
      sendError(session, "Empty sdpOffer, cannot proceed");
      return;
    }

    setupWebRTC(user, stream, jsonMessage.get("sdpOffer").getAsString());
  }

  private synchronized void setupWebRTC(final UserSession user, final StreamingRoom stream, final String sdpOffer) {
    // 2. WebRtcEndpoint
    // ICE candidates
    WebSocketSession session = user.getWs();
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

  private synchronized void pause(String sessionId) {
    UserSession user = users.get(sessionId);
    if (user == null) {
      return;
    }

    user.getRoom().pause(user);
  }

  private synchronized void resume(final WebSocketSession session) {
    UserSession user = users.get(session.getId());
    if (user == null) {
      return;
    }

    user.getRoom().resume(user);
  }

  private synchronized void stop(String sessionId) {
    UserSession user = users.remove(sessionId);
    if (user != null) {
      user.getRoom().removeUser(user);
    }
  }

  private synchronized void debugDot(final WebSocketSession session) {
    UserSession user = users.get(session.getId());
    if (user != null) {
      System.out.println("media trans" + user.getRoom().getPlayerEndpoint().isMediaTranscoding(MediaType.VIDEO));
      System.out.println("play " + user.getWebRtcEndpoint().isMediaTranscoding(MediaType.VIDEO));
      System.out.println("pppp " + user.getHubPort().isMediaTranscoding(MediaType.VIDEO));
    }
  }

  private synchronized void doSeek(final WebSocketSession session, JsonObject jsonMessage) {
    UserSession user = users.get(session.getId());

    if (user != null) {
      try {
        JsonElement position = jsonMessage.get("position");
        if (position == null) {
          sendError(session, "You need to set a new position");
        }
        user.getRoom().seek(user, position.getAsLong());
      } catch (KurentoException e) {
        log.debug("The seek cannot be performed");
        JsonObject response = new JsonObject();
        response.addProperty("id", "seek");
        response.addProperty("message", "Seek failed");
        sendMessage(session, response.toString());
      }
    }
  }

  private synchronized void getPosition(final WebSocketSession session) {
    UserSession user = users.get(session.getId());

    if (user != null) {
      long position = user.getRoom().getPlayerEndpoint().getPosition();

      JsonObject response = new JsonObject();
      response.addProperty("id", "position");
      response.addProperty("position", position);
      sendMessage(session, response.toString());
    }
  }

  private synchronized void onIceCandidate(String sessionId, JsonObject jsonMessage) {
    UserSession user = users.get(sessionId);

    if (user != null) {
      JsonObject jsonCandidate = jsonMessage.get("candidate").getAsJsonObject();
      IceCandidate candidate =
          new IceCandidate(jsonCandidate.get("candidate").getAsString(), jsonCandidate
              .get("sdpMid").getAsString(), jsonCandidate.get("sdpMLineIndex").getAsInt());
      user.getWebRtcEndpoint().addIceCandidate(candidate);
    }
  }

  public synchronized void sendPlayEnd(WebSocketSession session) {
    if (users.containsKey(session.getId())) {
      JsonObject response = new JsonObject();
      response.addProperty("id", "playEnd");
      sendMessage(session, response.toString());
    }
  }

  private synchronized void sendError(WebSocketSession session, String message) {
    //if (users.containsKey(session.getId())) {
      JsonObject response = new JsonObject();
      response.addProperty("id", "error");
      response.addProperty("message", message);
      sendMessage(session, response.toString());
    //}
  }

  private void sendMessage(WebSocketSession session, String message) {
    try {
      synchronized (session) {
        session.sendMessage(new TextMessage(message));
      }
    } catch (IOException e) {
      log.error("Exception sending message", e);
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    stop(session.getId());
  }
}

/*
 *     This file is part of snapcast
 *     Copyright (C) 2014-2016  Johannes Pohl
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.badaix.snapcast.control;

import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;

import de.badaix.snapcast.control.json.Client;
import de.badaix.snapcast.control.json.Group;
import de.badaix.snapcast.control.json.ServerStatus;
import de.badaix.snapcast.control.json.Stream;
import de.badaix.snapcast.control.json.Volume;

/**
 * Created by johannes on 13.01.16.
 */
public class RemoteControl implements TcpClient.TcpClientListener {

    private static final String TAG = "RC";

    private TcpClient tcpClient;
    private long msgId;
    private RemoteControlListener listener;
    private String host;
    private int port;
    private HashMap<Long, String> pendingRequests;

    public RemoteControl(RemoteControlListener listener) {
        this.listener = listener;
        msgId = 0;
        pendingRequests = new HashMap<>();
    }

    public String getHost() {
        return host;
    }


    public int getPort() {
        return port;
    }


    public synchronized void connect(final String host, final int port) {
        if ((tcpClient != null) && tcpClient.isConnected()) {
            if (this.host.equals(host) && (this.port == port))
                return;
            else
                disconnect();
        }
        this.host = host;
        this.port = port;

        tcpClient = new TcpClient(this);
        tcpClient.start(host, port);
    }

    public void disconnect() {
        if ((tcpClient != null) && (tcpClient.isConnected()))
            tcpClient.stop();
        tcpClient = null;
        pendingRequests.clear();
    }

    public boolean isConnected() {
        return ((tcpClient != null) && tcpClient.isConnected());
    }

    @Override
    public void onMessageReceived(TcpClient tcpClient, String message) {
//        Log.d(TAG, "Msg received: " + message);
        try {
            JSONObject json = new JSONObject(message);

            if (json.has("id")) {
                /// Response
//                Log.d(TAG, "ID: " + json.getString("id"));
                long id = json.getLong("id");
                String request = "";
                synchronized (pendingRequests) {
                    if (pendingRequests.containsKey(id)) {
                        request = pendingRequests.get(id);
//                        Log.d(TAG, "Response to: " + request);
                        pendingRequests.remove(id);
                    }
                }

                if (listener == null)
                    return;

                if (json.has("error")) {
                    JSONObject error = json.getJSONObject("error");
                    Log.e(TAG, "error " + error.getInt("code") + ": " + error.getString("message"));
                }

                if (TextUtils.isEmpty(request)) {
                    Log.e(TAG, "request for id " + id + " not found");
                    return;
                }

                RpcEvent rpcEvent = RpcEvent.response;
                /// Response to a "Object.GetStatus" message
                if (request.equals("Client.GetStatus")) {
                    listener.onClientEvent(this, rpcEvent, new Client(json.getJSONObject("result")), ClientEvent.updated);
                } else if (request.equals("Group.GetStatus")) {
                    listener.onGroupUpdate(this, rpcEvent, new Group(json.getJSONObject("result")));
                } else if (request.equals("Server.GetStatus")) {
                    listener.onServerUpdate(this, rpcEvent, new ServerStatus(json.getJSONObject("result")));
                } else if (json.getJSONObject("result").has("method") && json.getJSONObject("result").has("params")) {
                    /// Response to a "Object.Set" message
                    JSONObject result = json.getJSONObject("result");
                    String method = result.getString("method");
                    if ("Client.OnUpdate".equals(method)) {
                        listener.onClientEvent(this, rpcEvent, new Client(result.getJSONObject("params")), ClientEvent.updated);
                    } else if ("Group.OnUpdate".equals(method)) {
                        listener.onGroupUpdate(this, rpcEvent, new Group(result.getJSONObject("params")));
                    } else if ("Server.OnUpdate".equals(method)) {
                        listener.onServerUpdate(this, rpcEvent, new ServerStatus(result.getJSONObject("params")));
                    }
                }
            } else {
                /// Notification
                if (listener == null)
                    return;
                RpcEvent rpcEvent = RpcEvent.notification;
                String method = json.getString("method");
                if (method.contains("Client.On")) {
                    listener.onClientEvent(this, rpcEvent, new Client(json.getJSONObject("params")), ClientEvent.fromString(method));
                } else if (method.equals("Stream.OnUpdate")) {
                    listener.onStreamUpdate(this, rpcEvent, new Stream(json.getJSONObject("params")));
                } else if (method.equals("Group.OnUpdate")) {
                    listener.onGroupUpdate(this, rpcEvent, new Group(json.getJSONObject("params")));
                } else if (method.equals("Server.OnUpdate")) {
                    listener.onServerUpdate(this, rpcEvent, new ServerStatus(json.getJSONObject("params")));
                }
            }

        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onConnecting(TcpClient tcpClient) {
        Log.d(TAG, "onConnecting");
        if (listener != null)
            listener.onConnecting(this);
    }

    @Override
    public void onConnected(TcpClient tcpClient) {
        Log.d(TAG, "onConnected");
        if (listener != null)
            listener.onConnected(this);
    }

    @Override
    public void onDisconnected(TcpClient tcpClient, Exception e) {
        Log.d(TAG, "onDisconnected");
        if (listener != null)
            listener.onDisconnected(this, e);
    }

    private JSONObject jsonRequest(String method, JSONObject params) {
        JSONObject request = new JSONObject();
        try {
            request.put("jsonrpc", "2.0");
            request.put("method", method);
            request.put("id", msgId);
            if (params != null)
                request.put("params", params);
            synchronized (pendingRequests) {
                pendingRequests.put(msgId, method);
            }
            msgId++;
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return request;
    }

    public void getServerStatus() {
        JSONObject request = jsonRequest("Server.GetStatus", null);
        tcpClient.sendMessage(request.toString());
    }

    public void setName(Client client, String name) {
        try {
            JSONObject request = jsonRequest("Client.SetName", new JSONObject("{\"client\": \"" + client.getId() + "\", \"name\": \"" + name + "\"}"));
            tcpClient.sendMessage(request.toString());
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void setLatency(Client client, int latency) {
        try {
            JSONObject request = jsonRequest("Client.SetLatency", new JSONObject("{\"client\": \"" + client.getId() + "\", \"latency\": " + latency + "}"));
            tcpClient.sendMessage(request.toString());
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void setStream(Group group, String id) {
        setStream(group.getId(), id);
    }

    public void setClients(String groupId, ArrayList<String> clientIds) {
        try {
            JSONArray clients = new JSONArray();
            for (String clientId : clientIds)
                clients.put(clientId);
            JSONObject body = new JSONObject();
            body.put("group", groupId);
            body.put("clients", clients);
            JSONObject request = jsonRequest("Group.SetClients", body);
            tcpClient.sendMessage(request.toString());
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void setStream(String groupId, String id) {
        try {
            JSONObject request = jsonRequest("Group.SetStream", new JSONObject("{\"group\": \"" + groupId + "\", \"id\": \"" + id + "\"}"));
            tcpClient.sendMessage(request.toString());
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void setVolume(Client client, int percent, boolean mute) {
        try {
            Volume volume = new Volume(percent, mute);
            JSONObject body = new JSONObject();
            body.put("client", client.getId());
            body.put("volume", volume.toJson());
            JSONObject request = jsonRequest("Client.SetVolume", body);
            tcpClient.sendMessage(request.toString());
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void delete(Client client) {
        try {
            JSONObject request = jsonRequest("Server.DeleteClient", new JSONObject("{\"client\": \"" + client.getId() + "\"}"));
            tcpClient.sendMessage(request.toString());
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public enum ClientEvent {
        connected("Client.OnConnect"),
        disconnected("Client.OnDisconnect"),
        updated("Client.OnUpdate"),
        deleted("Client.OnDelete");
        private String text;

        ClientEvent(String text) {
            this.text = text;
        }

        public static ClientEvent fromString(String text) {
            if (text != null) {
                for (ClientEvent b : ClientEvent.values()) {
                    if (text.equalsIgnoreCase(b.text)) {
                        return b;
                    }
                }
            }
            throw new IllegalArgumentException("No ClientEvent with text " + text + " found");
        }

        public String getText() {
            return this.text;
        }
    }

    public enum RpcEvent {
        response,
        notification
    }

    public interface RemoteControlListener {
        void onConnected(RemoteControl remoteControl);

        void onConnecting(RemoteControl remoteControl);

        void onDisconnected(RemoteControl remoteControl, Exception e);

        void onClientEvent(RemoteControl remoteControl, RpcEvent rpcEvent, Client client, ClientEvent event);

        void onServerUpdate(RemoteControl remoteControl, RpcEvent rpcEvent, ServerStatus serverStatus);

        void onStreamUpdate(RemoteControl remoteControl, RpcEvent rpcEvent, Stream stream);

        void onGroupUpdate(RemoteControl remoteControl, RpcEvent rpcEvent, Group group);
    }
}

package tech.indicio.ariesmobileagentandroid.connections;

import android.net.Uri;
import android.util.Base64;
import android.util.Log;
import android.util.Pair;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.hyperledger.indy.sdk.IndyException;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Date;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import tech.indicio.ariesmobileagentandroid.IndySdkRejectResponse;
import tech.indicio.ariesmobileagentandroid.IndyWallet;
import tech.indicio.ariesmobileagentandroid.connections.diddoc.Authentication;
import tech.indicio.ariesmobileagentandroid.connections.diddoc.DIDDoc;
import tech.indicio.ariesmobileagentandroid.connections.diddoc.IndyService;
import tech.indicio.ariesmobileagentandroid.connections.diddoc.PublicKey;
import tech.indicio.ariesmobileagentandroid.connections.messages.ConnectionRequest;
import tech.indicio.ariesmobileagentandroid.connections.messages.ConnectionResponse;
import tech.indicio.ariesmobileagentandroid.connections.messages.InvitationMessage;
import tech.indicio.ariesmobileagentandroid.connections.messages.TrustPingMessage;
import tech.indicio.ariesmobileagentandroid.messaging.BaseMessage;
import tech.indicio.ariesmobileagentandroid.messaging.BasicMessage;
import tech.indicio.ariesmobileagentandroid.messaging.MessageListener;
import tech.indicio.ariesmobileagentandroid.messaging.MessageSender;
import tech.indicio.ariesmobileagentandroid.storage.Storage;

public class Connections extends MessageListener {

    private static final String TAG = "AMAA-Connections";
    private final IndyWallet indyWallet;
    private final MessageSender messageSender;
    private final Storage storage;
    //Add supported message classes in constructor.
    private final HashMap<String, Class<? extends BaseMessage>> supportedMessages = new HashMap<>();

    public Connections(IndyWallet indyWallet, MessageSender messageSender, Storage storage) {
        Log.d(TAG, "Creating Connections service");
        this.indyWallet = indyWallet;
        this.messageSender = messageSender;
        this.storage = storage;

        //Register supported messages
        this.supportedMessages.put(ConnectionRequest.type, ConnectionRequest.class);
        this.supportedMessages.put(ConnectionResponse.type, ConnectionResponse.class);

    }

    public HashMap<String, Class<? extends BaseMessage>> _getSupportedMessages() {
        return this.supportedMessages;
    }

    public ConnectionRecord receiveInvitationUrl(String invitationUrl, boolean autoAcceptConnection) throws Exception {
        Log.d(TAG, "Decoding invitation url: " + invitationUrl);
        Uri invitationUri = Uri.parse(invitationUrl);
        String encodedInvitation = invitationUri.getQueryParameter("c_i");

        byte[] invitationBytes = Base64.decode(encodedInvitation, Base64.NO_WRAP | Base64.URL_SAFE);
        String decodedInvitation = new String(invitationBytes);

        Gson gson = new Gson();
        InvitationMessage invitationMessage = gson.fromJson(decodedInvitation, InvitationMessage.class);
        Log.d(TAG, "Invitation message decoded:\n" + decodedInvitation);

        JsonObject recordTags = new JsonObject();

        recordTags.addProperty("invitationKey",
                invitationMessage.recipientKeys != null && invitationMessage.recipientKeys[0] != null
        );

        ConnectionRecord connectionRecord = new ConnectionRecord(
                UUID.randomUUID().toString(),
                new Date(),
                invitationMessage,
                ConnectionRecord.ConnectionState.INVITED,
                autoAcceptConnection,
                "invitee",
                "AMAA Agent",
                recordTags
        );

        storage.storeRecord(connectionRecord);

        return sendRequest(connectionRecord);
    }

    //Default autoAcceptConnection to true
    public ConnectionRecord receiveInvitationUrl(String invitationUrl) throws Exception {
        return receiveInvitationUrl(invitationUrl, true);
    }


    private ConnectionRecord sendRequest(ConnectionRecord connectionRecord) throws InterruptedException, ExecutionException, IndyException, JSONException {
        Log.d(TAG, "Creating Connection Request");

        connectionRecord = createConnection(connectionRecord);
        Connection connection = new Connection(connectionRecord.didDoc.id, connectionRecord.didDoc);

        ConnectionRequest connectionRequest = new ConnectionRequest(connectionRecord.label, connection, connectionRecord.id);

        this.messageSender.sendMessage(connectionRequest, connectionRecord);

        return connectionRecord;
    }

    private ConnectionRecord createConnection(ConnectionRecord connectionRecord) throws InterruptedException, ExecutionException, IndyException {
        try {
            Log.d(TAG, "Creating Connection");
            Log.d(TAG, "Creating DID and DIDDoc");
            Pair<String, String> didVerkey = this.indyWallet.generateDID();
            String did = didVerkey.first;
            String verkey = didVerkey.second;

            DIDDoc didDoc = DIDDoc.createDefaultDIDDoc(did, verkey);
            Log.d(TAG, "DIDDoc Created");

            //Update connection record
            connectionRecord.state = ConnectionRecord.ConnectionState.REQUESTED;
            connectionRecord.did = did;
            connectionRecord.didDoc = didDoc;
            connectionRecord.verkey = verkey;
            storage.updateRecord(connectionRecord);

            return connectionRecord;
        } catch (Exception e) {
            Log.e(TAG, "Error while creating Connection");
            throw e;
        }
    }

    @Override
    public void _callback(String type, BaseMessage message) {
        switch (type) {
            case InvitationMessage.type:
                invitationMessageHandler(message);
                break;
            case ConnectionResponse.type:
                processResponse((ConnectionResponse) message);
                break;
        }
    }

    private void invitationMessageHandler(BaseMessage message) {

    }

    private void processResponse(ConnectionResponse connectionResponse){
        try {
            ConnectionRecord connectionRecord = this.retrieveConnectionRecord(connectionResponse.thread.thid);

            String signerVerkey = connectionResponse.signedConnection.signer;
            String invitationVerkey = connectionRecord.invitation.recipientKeys[0];

            if(!signerVerkey.equals(invitationVerkey)){
                throw new Error("Connection in connection response is not signed with same key as recipient key in invitation");
            }

            connectionRecord.theirDidDoc = connectionResponse.connection.didDoc;
            connectionRecord.theirDid = connectionResponse.connection.did;
            connectionRecord.threadId = connectionResponse.thread.thid;
            connectionRecord.state = ConnectionRecord.ConnectionState.RESPONDED;
            storage.updateRecord(connectionRecord);

            //Send ack
            TrustPingMessage trustPing = new TrustPingMessage();
            this.messageSender.sendMessage(trustPing, connectionRecord);
            connectionRecord.state = ConnectionRecord.ConnectionState.COMPLETE;
            storage.updateRecord(connectionRecord);

            BasicMessage bm =  new BasicMessage("Hello there new connection!");
            this.messageSender.sendMessage(bm, connectionRecord);

        } catch (IndyException e) {
            IndySdkRejectResponse rejectResponse = new IndySdkRejectResponse(e);
            String code = rejectResponse.getCode();
            String json = rejectResponse.toJson();
            Log.e(TAG, "INDY ERROR");
            Log.e(TAG, code);
            Log.e(TAG, json);
            Log.e(TAG, e.getMessage());
        } catch(Exception e){
            e.printStackTrace();
        }
    }

    public ConnectionRecord retrieveConnectionRecord(String id) throws IndyException, ExecutionException, JSONException, InterruptedException {
        try {
            return (ConnectionRecord) storage.retrieveRecord(ConnectionRecord.type, id);
        } catch (Exception e) {
            throw e;
        }
    }

}

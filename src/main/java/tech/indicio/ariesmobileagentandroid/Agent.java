package tech.indicio.ariesmobileagentandroid;


import android.util.Log;

import org.hyperledger.indy.sdk.IndyException;
import org.hyperledger.indy.sdk.pool.Pool;
import org.hyperledger.indy.sdk.wallet.Wallet;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ExecutionException;


public class Agent {
    private static final String TAG = "AMAA-Agent";

    public String agentId;

    private String walletKey;
    private Wallet wallet;
    private Pool pool;

    private String ledgerConfig;

    /**
     * @return walletConfig
     * @throws JSONException
     */
    private String getWalletConfig() throws JSONException {
        return new JSONObject()
                .put("id", this.agentId)
                .toString();
    }

    /**
     * @return walletCredentials
     * @throws JSONException
     */
    private String getWalletCredentials() throws JSONException {
        return new JSONObject()
                .put("key", this.walletKey)
                .toString();
    }

    /**
     * @param configJson stringified json config file {
     *      "agentId": String - identifier for indy wallet.
     *      "walletKey": String - agent encryption key.
     *      "ledgerConfig": (optional) ledger config json {
     *          ledgerName: String - Name for ledger pool.
     *          genesisFileLocation: String - File location of downloaded genesis file.
     *      }
     *  }
     */
    public Agent(String configJson) {
        JSONObject config;
        try {
            config = new JSONObject(configJson);
            this.agentId = config.getString("agentId");
            this.walletKey = config.getString("walletKey");
        } catch (JSONException e) {
            throw new Error("Invalid config JSON");
        }

        try {
            //Load indy library
            Log.d(TAG, "Loading indy");
            System.loadLibrary("indy");
            Log.d(TAG, "Indy loaded, creating wallet...");

            //Load wallet
            this.wallet = this.openWallet(getWalletConfig(), getWalletCredentials());

            //Load pool if exists
            if (config.has("ledgerConfig")) {
                this.ledgerConfig = config.getString("ledgerConfig");
                this.pool = this.openPool(this.ledgerConfig);
            }


        } catch (Exception e) {
            IndySdkRejectResponse rejectResponse = new IndySdkRejectResponse(e);
            String code = rejectResponse.getCode();
            String json = rejectResponse.toJson();
            Log.e(TAG, "INDY ERROR");
            Log.e(TAG, code);
            Log.e(TAG, json);
            Log.e(TAG, e.getMessage());
        }

    }


    private void createPool(String ledgerName, String poolConfig) throws IndyException, ExecutionException, InterruptedException {
        Log.d(TAG, "Creating ledger pool " + ledgerName);
        Pool.createPoolLedgerConfig(ledgerName, poolConfig).get();
        Log.d(TAG, "Ledger " + ledgerName + ": created");
    }

    private Pool openPool(String ledgerConfig) throws IndyException, ExecutionException, InterruptedException, JSONException {
        try {
            Pool pool;
            JSONObject ledgerObj = new JSONObject(ledgerConfig);
            String genesisFilePath = ledgerObj.getString("genesisFileLocation");
            String ledgerName = ledgerObj.getString("ledgerName");

            //Create Json string for ledger pool
            String poolConfig = new JSONObject()
                    .put("genesis_txn", genesisFilePath)
                    .toString();
            Log.d(TAG, poolConfig);

            try {
                //Open pool
                Log.d(TAG, "Opening ledger pool");
                pool = Pool.openPoolLedger(ledgerName, null).get();
            } catch (Exception e) {
                IndySdkRejectResponse rejectResponse = new IndySdkRejectResponse(e);
                if (rejectResponse.getCode().equals("300")) {
                    try {
                        createPool(ledgerName, poolConfig);
                        Log.d(TAG, "Retrying to open ledger pool");
                        pool = Pool.openPoolLedger(ledgerName, null).get();
                    } catch (IndyException f) {
                        throw f;
                    }
                } else {
                    Log.d(TAG, "code: " + rejectResponse.getCode());
                    Log.d(TAG, "Failed to open ledger pool, reason: " + rejectResponse.getMessage());
                    throw e;
                }
            }
            Log.d(TAG, "Ledger " + ledgerName + ": opened");
            return pool;
        } catch (Exception e) {
            throw e;
        }
    }

    private void createWallet(String walletConfig, String walletCredentials) throws IndyException, ExecutionException, InterruptedException {
        //Try to create wallet, will fail if wallet already exists
        Log.d(TAG, "Creating wallet");
        Wallet.createWallet(walletConfig, walletCredentials).get();
        Log.d(TAG, "Wallet created");
    }

    private Wallet openWallet(String walletConfig, String walletCredentials) throws IndyException, ExecutionException, InterruptedException {
        //204 wallet not found
        //203 wallet already exists
        try {
            Wallet wallet;
            try {
                //Open wallet
                Log.d(TAG, "Wallet opening");
                wallet = Wallet.openWallet(walletConfig, walletCredentials).get();
            } catch (Exception e) {
                IndySdkRejectResponse rejectResponse = new IndySdkRejectResponse(e);
                if (rejectResponse.getCode().equals("204")) {
                    Log.d(TAG, "Wallet not found");
                    createWallet(walletConfig, walletCredentials);
                    //Open wallet
                    Log.d(TAG, "Retrying to open wallet");
                    wallet = Wallet.openWallet(walletConfig, walletCredentials).get();
                } else {
                    throw e;
                }
            }
            Log.d(TAG, "Wallet opened");
            return wallet;
        } catch (Exception e) {
            IndySdkRejectResponse rejectResponse = new IndySdkRejectResponse(e);
            Log.d(TAG, "Failed to open wallet, reason: " + rejectResponse.getMessage());
            throw e;
        }

    }

    public void deleteAgent() throws IndyException, ExecutionException, InterruptedException, JSONException {
        Wallet.deleteWallet(getWalletConfig(), getWalletCredentials()).get();
    }

    public void closeAgent() throws InterruptedException, ExecutionException, IndyException {
        if (pool != null) {
            this.pool.closePoolLedger().get();
        }
        if (wallet != null) {
            this.wallet.closeWallet().get();
        }

    }
}


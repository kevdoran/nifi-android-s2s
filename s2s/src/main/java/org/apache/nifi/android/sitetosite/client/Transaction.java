/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nifi.android.sitetosite.client;

import android.util.Log;

import org.apache.nifi.android.sitetosite.client.parser.TransactionResultParser;
import org.apache.nifi.android.sitetosite.client.peer.PeerConnectionManager;
import org.apache.nifi.android.sitetosite.client.protocol.CompressionOutputStream;
import org.apache.nifi.android.sitetosite.client.protocol.HttpMethod;
import org.apache.nifi.android.sitetosite.client.protocol.ResponseCode;
import org.apache.nifi.android.sitetosite.client.transaction.DataPacketWriter;
import org.apache.nifi.android.sitetosite.packet.DataPacket;
import org.apache.nifi.android.sitetosite.util.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Transaction for sending data to a NiFi instance
 */
public class Transaction {
    public static final String CANONICAL_NAME = Transaction.class.getCanonicalName();

    public static final String LOCATION_HEADER_NAME = "Location";
    public static final String EXPECTED_TRANSACTION_URL = "Expected header " + LOCATION_HEADER_NAME + " to contain transaction url.";
    public static final String LOCATION_URI_INTENT_NAME = "x-location-uri-intent";
    public static final String LOCATION_URI_INTENT_VALUE = "transaction-url";
    public static final String EXPECTED_TRANSACTION_URL_AS_INTENT = "Expected header " + LOCATION_URI_INTENT_NAME + " == " + LOCATION_URI_INTENT_VALUE;

    public static final String SERVER_SIDE_TRANSACTION_TTL = "x-nifi-site-to-site-server-transaction-ttl";
    public static final String UNABLE_TO_PARSE_TTL = "Unable to parse " + SERVER_SIDE_TRANSACTION_TTL + " as int: ";
    public static final String EXPECTED_TTL = "Expected " + SERVER_SIDE_TRANSACTION_TTL + " header";

    public static final String HANDSHAKE_PROPERTY_USE_COMPRESSION = "x-nifi-site-to-site-use-compression";
    public static final String HANDSHAKE_PROPERTY_REQUEST_EXPIRATION = "x-nifi-site-to-site-request-expiration";
    public static final String HANDSHAKE_PROPERTY_BATCH_COUNT = "x-nifi-site-to-site-batch-count";
    public static final String HANDSHAKE_PROPERTY_BATCH_SIZE = "x-nifi-site-to-site-batch-size";
    public static final String HANDSHAKE_PROPERTY_BATCH_DURATION = "x-nifi-site-to-site-batch-duration";

    private static final Map<String, String> BEGIN_TRANSACTION_HEADERS = initBeginTransactionHeaders();
    private static final Map<String, String> END_TRANSACTION_HEADERS = initEndTransactionHeaders();
    private static final Pattern NIFI_API_PATTERN = Pattern.compile(Pattern.quote("/nifi-api"));
    public static final String CONTENT_TYPE = "Content-Type";
    public static final String APPLICATION_OCTET_STREAM = "application/octet-stream";
    public static final String ACCEPT = "Accept";
    public static final String TEXT_PLAIN = "text/plain";

    private final Map<String, String> handshakeProperties;
    private final String transactionUrl;
    private final PeerConnectionManager peerConnectionManager;
    private final HttpURLConnection sendFlowFilesConnection;
    private final DataPacketWriter dataPacketWriter;
    private final ScheduledFuture<?> ttlExtendFuture;

    public Transaction(PeerConnectionManager peerConnectionManager, String portIdentifier, SiteToSiteClientConfig siteToSiteClientConfig, ScheduledExecutorService ttlExtendTaskExecutor) throws IOException {
        this.peerConnectionManager = peerConnectionManager;
        this.handshakeProperties = createHandshakeProperties(siteToSiteClientConfig);

        HttpURLConnection createTransactionConnection = peerConnectionManager.openConnection("/data-transfer/input-ports/" + portIdentifier + "/transactions", handshakeProperties, HttpMethod.POST);
        int responseCode = createTransactionConnection.getResponseCode();
        if (responseCode < 200 || responseCode > 299) {
            throw new IOException("Got response code " + responseCode);
        }

        int ttl;
        if (LOCATION_URI_INTENT_VALUE.equals(createTransactionConnection.getHeaderField(LOCATION_URI_INTENT_NAME))) {
            String ttlString = createTransactionConnection.getHeaderField(SERVER_SIDE_TRANSACTION_TTL);
            if (ttlString == null || ttlString.isEmpty()) {
                throw new IOException(EXPECTED_TTL);
            } else {
                try {
                    ttl = Integer.parseInt(ttlString);
                } catch (Exception e) {
                    throw new IOException(UNABLE_TO_PARSE_TTL + ttlString, e);
                }
            }
            String transactionFullUrl = createTransactionConnection.getHeaderField(LOCATION_HEADER_NAME);
            if (transactionFullUrl == null) {
                throw new IOException(EXPECTED_TRANSACTION_URL);
            }
            String path = new URL(transactionFullUrl).getPath();
            this.transactionUrl = NIFI_API_PATTERN.matcher(path).replaceFirst("");
        } else {
            throw new IOException(EXPECTED_TRANSACTION_URL_AS_INTENT);
        }

        Map<String, String> beginTransactionHeaders = new HashMap<>(BEGIN_TRANSACTION_HEADERS);
        beginTransactionHeaders.putAll(handshakeProperties);
        sendFlowFilesConnection = peerConnectionManager.openConnection(transactionUrl + "/flow-files", beginTransactionHeaders, HttpMethod.POST);
        OutputStream outputStream = sendFlowFilesConnection.getOutputStream();
        if (siteToSiteClientConfig.isUseCompression()) {
            outputStream = new CompressionOutputStream(outputStream);
        }
        dataPacketWriter = new DataPacketWriter(outputStream);
        ttlExtendFuture = ttlExtendTaskExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    HttpURLConnection ttlExtendConnection = Transaction.this.peerConnectionManager.openConnection(transactionUrl, handshakeProperties, HttpMethod.PUT);
                    try {
                        int responseCode = ttlExtendConnection.getResponseCode();
                        if (responseCode < 200 || responseCode > 299) {
                            Log.e(CANONICAL_NAME, "Extending ttl failed for transaction (responseCode " + responseCode + ")" + transactionUrl);
                        }
                    } finally {
                        ttlExtendConnection.disconnect();
                    }
                } catch (IOException e) {
                    Log.e(CANONICAL_NAME, "Error extending transaction ttl.", e);
                }
            }
        }, ttl / 2, ttl / 2, TimeUnit.SECONDS);
    }

    private Map<String, String> createHandshakeProperties(SiteToSiteClientConfig siteToSiteClientConfig) {
        Map<String, String> handshakeProperties = new HashMap<>();

        if (siteToSiteClientConfig.isUseCompression()) {
            handshakeProperties.put(HANDSHAKE_PROPERTY_USE_COMPRESSION, "true");
        }

        long requestExpirationMillis = siteToSiteClientConfig.getIdleConnectionExpiration(TimeUnit.MILLISECONDS);
        if (requestExpirationMillis > 0) {
            handshakeProperties.put(HANDSHAKE_PROPERTY_REQUEST_EXPIRATION, String.valueOf(requestExpirationMillis));
        }

        int batchCount = siteToSiteClientConfig.getPreferredBatchCount();
        if (batchCount > 0) {
            handshakeProperties.put(HANDSHAKE_PROPERTY_BATCH_COUNT, String.valueOf(batchCount));
        }

        long batchSize = siteToSiteClientConfig.getPreferredBatchSize();
        if (batchSize > 0) {
            handshakeProperties.put(HANDSHAKE_PROPERTY_BATCH_SIZE, String.valueOf(batchSize));
        }

        long batchDurationMillis = siteToSiteClientConfig.getPreferredBatchDuration(TimeUnit.MILLISECONDS);
        if (batchDurationMillis > 0) {
            handshakeProperties.put(HANDSHAKE_PROPERTY_BATCH_DURATION, String.valueOf(batchDurationMillis));
        }

        return Collections.unmodifiableMap(handshakeProperties);
    }

    private static Map<String, String> initEndTransactionHeaders() {
        Map<String, String> result = new HashMap<>();
        result.put(CONTENT_TYPE, APPLICATION_OCTET_STREAM);
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, String> initBeginTransactionHeaders() {
        Map<String, String> result = new HashMap<>();
        result.put(CONTENT_TYPE, APPLICATION_OCTET_STREAM);
        result.put(ACCEPT, TEXT_PLAIN);
        return Collections.unmodifiableMap(result);
    }

    /**
     * Sends the dataPacket to NiFi
     *
     * @param dataPacket the dataPacket
     * @throws IOException if there is an error sending it
     */
    public void send(DataPacket dataPacket) throws IOException {
        dataPacketWriter.write(dataPacket);
    }

    /**
     * Confirms the sent data and verifies the checksum
     *
     * @throws IOException if there is a problem confirming or verifying the checksum
     */
    public void confirm() throws IOException {
        long calculatedCrc = dataPacketWriter.close();
        int responseCode = sendFlowFilesConnection.getResponseCode();
        if (responseCode != 200 && responseCode != 202) {
            throw new IOException("Got response code " + responseCode);
        }
        long serverCrc = IOUtils.readInputStreamAndParseAsLong(sendFlowFilesConnection.getInputStream());
        if (calculatedCrc != serverCrc) {
            endTransaction(ResponseCode.BAD_CHECKSUM);
            throw new IOException("Should have " + calculatedCrc + " for crc, got " + serverCrc);
        }
    }

    /**
     * Completes the transaction
     *
     * @return a transaction result
     * @throws IOException if there is a problem completing the transaction
     */
    public TransactionResult complete() throws IOException {
        return endTransaction(ResponseCode.CONFIRM_TRANSACTION);
    }

    /**
     * Cancels the transaction
     *
     * @return a transaction result
     * @throws IOException if there is a problem canceling the transaction
     */
    public TransactionResult cancel() throws IOException {
        return endTransaction(ResponseCode.CANCEL_TRANSACTION);
    }

    private TransactionResult endTransaction(ResponseCode responseCodeToSend) throws IOException {
        ttlExtendFuture.cancel(false);
        try {
            ttlExtendFuture.get();
        } catch (Exception e) {
            if (!(e instanceof CancellationException)) {
                throw new IOException("Error waiting on ttl extension thread to end.", e);
            }
        }
        sendFlowFilesConnection.disconnect();
        Map<String, String> queryParameters = new HashMap<>();
        queryParameters.put("responseCode", Integer.toString(responseCodeToSend.getCode()));
        Map<String, String> endTransactionHeaders = new HashMap<>(END_TRANSACTION_HEADERS);
        endTransactionHeaders.putAll(handshakeProperties);
        HttpURLConnection delete = peerConnectionManager.openConnection(transactionUrl, endTransactionHeaders, queryParameters, HttpMethod.DELETE);
        try {
            int responseCode = delete.getResponseCode();
            if (responseCode < 200 || responseCode > 299) {
                throw new IOException("Got response code " + responseCode);
            }
            InputStream inputStream = delete.getInputStream();
            try {
                return TransactionResultParser.parseTransactionResult(inputStream);
            } finally {
                inputStream.close();
            }
        } finally {
            delete.disconnect();
        }
    }
}

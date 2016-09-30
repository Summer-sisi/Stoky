/*
 * Copyright 2015 floragunn UG (haftungsbeschränkt)
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
 * 
 */

package com.floragunn.searchguard.filter;

import javax.net.ssl.SSLPeerUnverifiedException;

import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestFilter;
import org.elasticsearch.rest.RestFilterChain;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestRequest.Method;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.threadpool.ThreadPool;

import com.floragunn.searchguard.auditlog.AuditLog;
import com.floragunn.searchguard.auth.BackendRegistry;
import com.floragunn.searchguard.ssl.util.SSLRequestHelper;
import com.floragunn.searchguard.ssl.util.SSLRequestHelper.SSLInfo;
import com.floragunn.searchguard.support.HeaderHelper;

public class SearchGuardRestFilter extends RestFilter {

    private final BackendRegistry registry;
    private final AuditLog auditLog;
    private final ThreadContext threadContext;

    @Inject
    public SearchGuardRestFilter(final BackendRegistry registry, final AuditLog auditLog, final ThreadPool threadPool) {
        super();
        this.registry = registry;
        this.auditLog = auditLog;
        this.threadContext = threadPool.getThreadContext();
    }

    @Override
    public void process(RestRequest request, RestChannel channel, NodeClient client, RestFilterChain filterChain) throws Exception {

        try {
            HeaderHelper.checkSGHeader(this.threadContext);
        } catch (final Exception e) {
            auditLog.logBadHeaders(request);
            channel.sendResponse(new BytesRestResponse(channel, RestStatus.FORBIDDEN, e));
            return;
        }

        final SSLInfo sslInfo;
        try {
            if((sslInfo = SSLRequestHelper.getSSLInfo(request)) != null) {
                if(sslInfo.getPrincipal() != null) {
                    threadContext.putTransient("_sg_ssl_principal", sslInfo.getPrincipal());
                }
                
                if(sslInfo.getX509Certs() != null) {
                     threadContext.putTransient("_sg_ssl_peer_certificates", sslInfo.getX509Certs());
                }
                threadContext.putTransient("_sg_ssl_protocol", sslInfo.getProtocol());
                threadContext.putTransient("_sg_ssl_cipher", sslInfo.getCipher());
            }
        } catch (SSLPeerUnverifiedException e) {
            //logger.error("No client certificates found but such are needed (SG 8).");
            //errorThrown(e, request);
            throw ExceptionsHelper.convertToElastic(e);
        }

        if(request.method() != Method.OPTIONS) {
            if (!registry.authenticate(request, channel, threadContext)) {
                // another roundtrip
                return;
            }
        }

        filterChain.continueProcessing(request, channel, client);
    }
}

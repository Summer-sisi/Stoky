/*
 * Copyright 2017 floragunn GmbH
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

package com.floragunn.searchguard.configuration;

import java.util.Map;
import java.util.Set;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.threadpool.ThreadPool;

import com.floragunn.searchguard.user.User;

public class PrivilegesInterceptor {

    protected final IndexNameExpressionResolver resolver;
    protected final ClusterService clusterService;
    protected final Client client;
    protected final ThreadPool threadPool;

    public PrivilegesInterceptor(final IndexNameExpressionResolver resolver, final ClusterService clusterService, 
            final Client client, ThreadPool threadPool) {
        this.resolver = resolver;
        this.clusterService = clusterService;
        this.client = client;
        this.threadPool = threadPool;
    }

    public boolean replaceKibanaIndex(final ActionRequest request, final String action, final User user, final Settings config, final Set<String> requestedResolvedIndices, final Map<String, Boolean> tenants) { 
        throw new RuntimeException("not implemented");
        //return false;
    }
    
    public boolean replaceAllowedIndices(final ActionRequest request, final String action, final User user, final Settings config, final Set<PrivilegesEvaluator.IndexType> leftOvers) {
        throw new RuntimeException("not implemented");
        //return false;   
    }
    
    protected ThreadContext getThreadContext() {
        return threadPool.getThreadContext();
    }
}

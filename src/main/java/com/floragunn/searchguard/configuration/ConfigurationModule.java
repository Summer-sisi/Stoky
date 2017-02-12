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

package com.floragunn.searchguard.configuration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.inject.AbstractModule;

import com.floragunn.searchguard.auth.internal.InternalAuthenticationBackend;
import com.floragunn.searchguard.configuration.DlsFlsRequestValve.NoopDlsFlsRequestValve;
import com.floragunn.searchguard.transport.InterClusterRequestEvaluator;
import com.floragunn.searchguard.transport.InterClusterRequestEvaluatorProvider;

public class ConfigurationModule extends AbstractModule {

    protected final Logger log = LogManager.getLogger(this.getClass());
    
    @Override
    protected void configure() {
        bind(AdminDNs.class);
        bind(ActionGroupHolder.class).asEagerSingleton();
        bind(PrivilegesEvaluator.class).asEagerSingleton();
        bind(InternalAuthenticationBackend.class).asEagerSingleton();
        bind(InterClusterRequestEvaluator.class)
            .toProvider(InterClusterRequestEvaluatorProvider.class)
            .asEagerSingleton();
        
        try {
            Class dlsFlsRequestValve;
            if ((dlsFlsRequestValve = Class
                    .forName("com.floragunn.searchguard.configuration.DlsFlsValveImpl")) != null) {
                bind(DlsFlsRequestValve.class).to(dlsFlsRequestValve).asEagerSingleton();
                log.info("FLS/DLS valve bound");
            } else {
                throw new ClassNotFoundException();
            }
        } catch (ClassNotFoundException e) {
            bind(DlsFlsRequestValve.class).to(NoopDlsFlsRequestValve.class).asEagerSingleton();
            log.info("FLS/DLS valve not bound (noop)");
        }
        
        try {
            Class privilegesInterceptorImpl;
            if ((privilegesInterceptorImpl = Class
                    .forName("com.floragunn.searchguard.configuration.PrivilegesInterceptorImpl")) != null) {
                bind(PrivilegesInterceptor.class).to(privilegesInterceptorImpl).asEagerSingleton();
                log.info("Privileges interceptor bound");
            } else {
                throw new ClassNotFoundException();
            }
        } catch (ClassNotFoundException e) {
            bind(PrivilegesInterceptor.class).asEagerSingleton();
            log.info("Privileges interceptor not bound (noop)");
        }
               
    }
    
    
}

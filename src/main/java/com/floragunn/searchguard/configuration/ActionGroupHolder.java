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

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.elasticsearch.common.settings.Settings;

import com.floragunn.searchguard.support.ConfigConstants;

public class ActionGroupHolder {

    final ConfigurationRepository configurationRepository;

    public ActionGroupHolder(final ConfigurationRepository configurationRepository) {
        this.configurationRepository = configurationRepository;
    }

    public Set<String> getGroupMembers(final String groupname) {

        final Settings actionGroups = getSettings();
        
        if (actionGroups == null) {
            return Collections.emptySet();
        }

        return resolve(actionGroups, groupname);
    }

    private Set<String> resolve(final Settings actionGroups, final String entry) {

        final Set<String> ret = new HashSet<String>();
        final String[] en = actionGroups.getAsArray(entry);
        for (int i = 0; i < en.length; i++) {
            final String string = en[i];
            if (actionGroups.names().contains(string)) {
                ret.addAll(resolve(actionGroups,string));
            } else {
                ret.add(string);
            }
        }
        return ret;
    }

    private Settings getSettings() {
        return configurationRepository.getConfiguration(ConfigConstants.CONFIGNAME_ACTION_GROUPS);
    }
}

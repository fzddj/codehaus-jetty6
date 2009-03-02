// ========================================================================
// Copyright 1996-2005 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at 
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// ========================================================================

package org.mortbay.jetty.server;
import java.security.Principal;
import java.util.Map;

import org.mortbay.jetty.server.RunAsToken;

/* ------------------------------------------------------------ */
/** User object that encapsulates user identity and operations such as run-as-role actions, checking isUserInRole and getUserPrincipal.
 *
 * Some of this functionality was previously in UserRealm detached from the user identity.
 *
 */
public interface UserIdentity
{
    Principal getUserPrincipal();

    String getAuthMethod();

    /* ------------------------------------------------------------ */
    /** Check if the user is in a role.
     * @param role A role name.
     * @return True if the user can act in that role.
     */
    boolean isUserInRole(String role);

    /* ------------------------------------------------------------ */
    /** Push role onto a Principal.
     * This method is used to set the run-as role.
     * @param newRunAsRole The role to set.
     * @return the previous run-as role so it can be reset on exit.
     */
    RunAsToken setRunAsRole(RunAsToken newRunAsRole);

    /**
     * set the role mapping for a particular servlet for role-refs.  Returns the preexisting value.
     *
     * Note: should not return null.
     *
     * @param roleMap Role reference map
     * @return previous rol reference map
     */
    Map<String,String> setRoleRefMap(Map<String,String> roleMap);


    /**
     * get the role mapping for a particular servlet for role-refs. 
     *
     * @return previous role reference map
     */
    Map<String,String> getRoleRefMap();

    
    public static final UserIdentity UNAUTHENTICATED_IDENTITY = new UserIdentity()
    {
        public Principal getUserPrincipal()
        {
            return null;
        }
        public String getAuthMethod() 
        {
            return null;
        }
        public boolean isUserInRole(String role)
        {
            return false;
        }
        public RunAsToken setRunAsRole(RunAsToken newRunAsRole)
        {
            return null;
        }
        public Map<String,String> setRoleRefMap(Map<String,String> roleMap)
        {
            return null;
        }
        public Map<String, String> getRoleRefMap()
        {
            return null;
        }
        
        public String toString()
        {
            return "UNAUTHENTICATED";
        }
    };
}
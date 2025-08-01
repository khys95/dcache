/* dCache - http://www.dcache.org/
 *
 * Copyright (C) 2017 Deutsches Elektronen-Synchrotron
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.dcache.auth;

import java.io.Serializable;
import java.security.Principal;

/**
 * A principal that indicates the user wishes to adopt a specific role. This principal in no way
 * specifies whether the user is entitled to have this role.
 */
@AuthenticationInput
public class DesiredRole implements Principal, Serializable {

    private static final long serialVersionUID = 1L;

    private final String role;

    public DesiredRole(String role) {
        this.role = role;
    }

    @Override
    public String getName() {
        return role;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + '[' + getName() + ']';
    }
}

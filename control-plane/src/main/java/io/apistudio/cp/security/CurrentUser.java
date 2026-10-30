package io.apistudio.cp.security;

/**
 * Pengguna yang sedang terautentikasi.
 *
 * @param id          id pengguna
 * @param username    username
 * @param displayName nama tampilan
 * @param role        peran
 */
public record CurrentUser(String id, String username, String displayName, Role role) {

    public boolean can(Permission permission) {
        return role != null && role.has(permission);
    }

    public String roleLabel() {
        return role == null ? "" : role.label();
    }
}

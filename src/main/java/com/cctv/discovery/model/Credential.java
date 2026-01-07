package com.cctv.discovery.model;

import java.io.Serializable;

/**
 * POJO representing a username/password credential pair.
 */
public class Credential implements Serializable {
    private static final long serialVersionUID = 1L;

    private String username;
    private String password;

    public Credential() {
    }

    public Credential(String username, String password) {
        this.username = username;
        this.password = password;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    @Override
    public String toString() {
        return username + " / " + maskPassword(password);
    }

    public String toDisplayString() {
        return username + " : " + password;
    }

    private String maskPassword(String pwd) {
        if (pwd == null || pwd.isEmpty()) {
            return "";
        }
        if (pwd.length() <= 2) {
            return "**";
        }
        return pwd.charAt(0) + "***" + pwd.charAt(pwd.length() - 1);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Credential that = (Credential) o;

        if (username != null ? !username.equals(that.username) : that.username != null) return false;
        return password != null ? password.equals(that.password) : that.password == null;
    }

    @Override
    public int hashCode() {
        int result = username != null ? username.hashCode() : 0;
        result = 31 * result + (password != null ? password.hashCode() : 0);
        return result;
    }
}

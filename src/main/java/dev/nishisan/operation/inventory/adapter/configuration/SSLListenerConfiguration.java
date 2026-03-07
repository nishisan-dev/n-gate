/*
 * Copyright (C) 2023 Lucas Nishimura <lucas.nishimura@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.nishisan.operation.inventory.adapter.configuration;

/**
 *
 * @author Lucas
 */
public class SSLListenerConfiguration {

    private String alias;
    private String keystoreFile;
    private String keystorePassword;
    private String truststoreFile;
    private String truststorePassword;
    private String certFile;
    private String keyFile;

    public String getCertFile() {
        return certFile;
    }

    public void setCertFile(String certFile) {
        this.certFile = certFile;
    }

    public String getKeyFile() {
        return keyFile;
    }

    public void setKeyFile(String keyFile) {
        this.keyFile = keyFile;
    }

    /**
     * @return the keystoreFile
     */
    public String getKeystoreFile() {
        return keystoreFile;
    }

    /**
     * @param keystoreFile the keystoreFile to set
     */
    public void setKeystoreFile(String keystoreFile) {
        this.keystoreFile = keystoreFile;
    }

    /**
     * @return the keystorePassword
     */
    public String getKeystorePassword() {
        return keystorePassword;
    }

    /**
     * @param keystorePassword the keystorePassword to set
     */
    public void setKeystorePassword(String keystorePassword) {
        this.keystorePassword = keystorePassword;
    }

    /**
     * @return the truststoreFile
     */
    public String getTruststoreFile() {
        return truststoreFile;
    }

    /**
     * @param truststoreFile the truststoreFile to set
     */
    public void setTruststoreFile(String truststoreFile) {
        this.truststoreFile = truststoreFile;
    }

    /**
     * @return the truststorePassword
     */
    public String getTruststorePassword() {
        return truststorePassword;
    }

    /**
     * @param truststorePassword the truststorePassword to set
     */
    public void setTruststorePassword(String truststorePassword) {
        this.truststorePassword = truststorePassword;
    }

    /**
     * @return the alias
     */
    public String getAlias() {
        return alias;
    }

    /**
     * @param alias the alias to set
     */
    public void setAlias(String alias) {
        this.alias = alias;
    }
}

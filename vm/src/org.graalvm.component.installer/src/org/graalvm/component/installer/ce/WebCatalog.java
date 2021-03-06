/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.component.installer.ce;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.NoRouteToHostException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarFile;
import org.graalvm.component.installer.BundleConstants;
import org.graalvm.component.installer.CommandInput;
import org.graalvm.component.installer.CommonConstants;
import org.graalvm.component.installer.Feedback;
import org.graalvm.component.installer.jar.JarMetaLoader;
import org.graalvm.component.installer.model.ComponentRegistry;
import org.graalvm.component.installer.model.ComponentStorage;
import org.graalvm.component.installer.remote.FileDownloader;
import org.graalvm.component.installer.persist.MetadataLoader;
import org.graalvm.component.installer.remote.RemotePropertiesStorage;
import org.graalvm.component.installer.SoftwareChannel;

/**
 *
 * @author sdedic
 */
public class WebCatalog implements SoftwareChannel {
    private CommandInput input;
    private Feedback feedback;
    private String urlString;
    private URL catalogURL;
    private ComponentRegistry local;

    @Override
    public boolean setupLocation(String url) {
        int schColon = url.indexOf(':');
        if (schColon == -1) {
            return false;
        }
        String scheme = url.toLowerCase().substring(0, schColon);
        if (acceptURLScheme(scheme)) {
            urlString = url;
            return true;
        } else {
            return false;
        }
    }

    protected boolean acceptURLScheme(String scheme) {
        switch (scheme) {
            case "http":
            case "https":
            case "ftp":
            case "ftps":
                return true;
        }
        return false;
    }

    @Override
    public void init(CommandInput in, Feedback out) {
        assert this.input == null;

        this.input = in;
        this.feedback = out.withBundle(WebCatalog.class);
        this.local = in.getLocalRegistry();
    }

    @SuppressWarnings("unchecked")
    @Override
    public ComponentRegistry getRegistry() {
        FileDownloader dn;
        try {
            catalogURL = new URL(urlString);
            dn = new FileDownloader(feedback.l10n("REMOTE_CatalogLabel"), catalogURL, feedback);
            dn.download();
        } catch (MalformedURLException ex) {
            throw feedback.failure("REMOTE_InvalidURL", ex, catalogURL, ex.getLocalizedMessage());
        } catch (NoRouteToHostException | ConnectException ex) {
            throw feedback.failure("REMOTE_ErrorDownloadCatalogProxy", ex, catalogURL, ex.getLocalizedMessage());
        } catch (FileNotFoundException ex) {
            throw feedback.failure("REMOTE_ErrorDownloadCatalogNotFound", ex, catalogURL);
        } catch (IOException ex) {
            throw feedback.failure("REMOTE_ErrorDownloadCatalog", ex, catalogURL, ex.getLocalizedMessage());
        }

        StringBuilder sb = new StringBuilder();
        Map<String, String> graalCaps = local.getGraalCapabilities();
        sb.append(graalCaps.get(CommonConstants.CAP_GRAALVM_VERSION).toLowerCase());
        sb.append("_");
        sb.append(graalCaps.get(CommonConstants.CAP_OS_NAME).toLowerCase());
        sb.append("_");
        sb.append(graalCaps.get(CommonConstants.CAP_OS_ARCH).toLowerCase());

        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(dn.getLocalFile())) {
            props.load(fis);
        } catch (IllegalArgumentException | IOException ex) {
            throw feedback.failure("REMOTE_CorruptedCatalogFile", ex, catalogURL);
        }
        if (props.getProperty(BundleConstants.GRAAL_COMPONENT_ID + "." + sb.toString()) == null) {
            boolean graalPrefixFound = false;
            boolean componentFound = false;
            for (String s : Collections.list((Enumeration<String>) props.propertyNames())) {
                if (s.startsWith(BundleConstants.GRAAL_COMPONENT_ID)) {
                    graalPrefixFound = true;
                }
                if (s.startsWith("Component.")) {
                    componentFound = true;
                }
            }
            if (!(graalPrefixFound && componentFound)) {
                throw feedback.failure("REMOTE_CorruptedCatalogFile", null, catalogURL);
            } else {
                throw feedback.failure("REMOTE_UnsupportedGraalVersion", null,
                                graalCaps.get(CommonConstants.CAP_GRAALVM_VERSION),
                                graalCaps.get(CommonConstants.CAP_OS_NAME),
                                graalCaps.get(CommonConstants.CAP_OS_ARCH));
            }
        }
        ComponentStorage storage = new RemotePropertiesStorage(feedback, local, props, sb.toString(), catalogURL);
        return new ComponentRegistry(feedback, storage);
    }

    @Override
    public MetadataLoader createLocalFileLoader(Path localFile, boolean verify) throws IOException {
        return new JarMetaLoader(new JarFile(localFile.toFile(), verify), feedback);
    }

    @Override
    public FileDownloader configureDownloader(FileDownloader dn) {
        return dn;
    }
}

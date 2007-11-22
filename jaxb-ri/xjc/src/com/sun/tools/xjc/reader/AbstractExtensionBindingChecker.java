/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 * 
 * Contributor(s):
 * 
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.tools.xjc.reader;

import java.util.Set;
import java.util.HashSet;

import com.sun.tools.xjc.util.SubtreeCutter;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.Plugin;
import com.sun.xml.bind.v2.util.EditDistance;

import org.xml.sax.helpers.NamespaceSupport;
import org.xml.sax.Locator;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;
import org.xml.sax.SAXException;

/**
 * Common code between {@code DTDExtensionBindingChecker} and {@link ExtensionBindingChecker}.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class AbstractExtensionBindingChecker extends SubtreeCutter {
    /** Remembers in-scope namespace bindings. */
    protected final NamespaceSupport nsSupport = new NamespaceSupport();

    /**
     * Set of namespace URIs that designates enabled extensions.
     */
    protected final Set<String> enabledExtensions = new HashSet<String>();

    private final Set<String> recognizableExtensions = new HashSet<String>();

    private Locator locator;

    /**
     * Namespace URI of the target schema language. Elements in this
     * namespace are always allowed.
     */
    protected final String schemaLanguage;

    /**
     * If false, any use of extensions is reported as an error.
     */
    protected final boolean allowExtensions;

    private final Options options;

    /**
     * @param handler
     *      This error handler will receive detected errors.
     */
    public AbstractExtensionBindingChecker( String schemaLanguage, Options options, ErrorHandler handler ) {
        this.schemaLanguage = schemaLanguage;
        this.allowExtensions = options.compatibilityMode!=Options.STRICT;
        this.options = options;
        setErrorHandler(handler);

        for (Plugin plugin : options.getAllPlugins())
            recognizableExtensions.addAll(plugin.getCustomizationURIs());
        recognizableExtensions.add(Const.XJC_EXTENSION_URI);
    }

    /**
     * Verify that the given URI is indeed a valid extension namespace URI,
     * and if so enable it.
     * <p>
     * This method does all the error handling.
     */
    protected final void checkAndEnable(String uri) throws SAXException {
        if( !isRecognizableExtension(uri) ) {
            String nearest = EditDistance.findNearest(uri, recognizableExtensions);
            // not the namespace URI we know of
            error( Messages.ERR_UNSUPPORTED_EXTENSION.format(uri,nearest) );
        } else
        if( !isSupportedExtension(uri) ) {
            // recognizable but not not supported, meaning
            // the plug-in isn't enabled

            // look for plug-in that handles this URI
            Plugin owner = null;
            for( Plugin p : options.getAllPlugins() ) {
                if(p.getCustomizationURIs().contains(uri)) {
                    owner = p;
                    break;
                }
            }
            if(owner!=null)
                // we know the plug-in that supports this namespace, but it's not enabled
                error( Messages.ERR_PLUGIN_NOT_ENABLED.format(owner.getOptionName(),uri));
            else {
                // this shouldn't happen, but be defensive...
                error( Messages.ERR_UNSUPPORTED_EXTENSION.format(uri) );
            }
        }

        // as an error recovery enable this namespace URI anyway.
        enabledExtensions.add(uri);
    }

    /**
     * If the tag name belongs to a plugin namespace-wise, check its local name
     * to make sure it's correct.
     */
    protected final void verifyTagName(String namespaceURI, String localName, String qName) throws SAXException {
        if(options.pluginURIs.contains(namespaceURI)) {
            // make sure that this is a valid tag name
            boolean correct = false;
            for( Plugin p : options.activePlugins ) {
                if(p.isCustomizationTagName(namespaceURI,localName)) {
                    correct = true;
                    break;
                }
            }
            if(!correct) {
                error( Messages.ERR_ILLEGAL_CUSTOMIZATION_TAGNAME.format(qName) );
                startCutting();
            }
        }
    }

    /**
     * Checks if the given namespace URI is supported as the extension
     * bindings.
     */
    protected final boolean isSupportedExtension( String namespaceUri ) {
        return namespaceUri.equals(Const.XJC_EXTENSION_URI) || options.pluginURIs.contains(namespaceUri);
    }

    /**
     * Checks if the given namespace URI can be potentially recognized
     * by this XJC.
     */
    protected final boolean isRecognizableExtension( String namespaceUri ) {
        return recognizableExtensions.contains(namespaceUri);
    }


    public void setDocumentLocator(Locator locator) {
        super.setDocumentLocator(locator);
        this.locator = locator;
    }

    public void startDocument() throws SAXException {
        super.startDocument();

        nsSupport.reset();
        enabledExtensions.clear();
    }

    public void startPrefixMapping(String prefix, String uri) throws SAXException {
        super.startPrefixMapping(prefix, uri);
        nsSupport.pushContext();
        nsSupport.declarePrefix(prefix,uri);
    }

    public void endPrefixMapping(String prefix) throws SAXException {
        super.endPrefixMapping(prefix);
        nsSupport.popContext();
    }


    /**
     * Reports an error and returns the created SAXParseException
     */
    protected final SAXParseException error( String msg ) throws SAXException {
        SAXParseException spe = new SAXParseException( msg, locator );
        getErrorHandler().error(spe);
        return spe;
    }

    /**
     * Reports a warning.
     */
    protected final void warning( String msg ) throws SAXException {
        SAXParseException spe = new SAXParseException( msg, locator );
        getErrorHandler().warning(spe);
    }
}

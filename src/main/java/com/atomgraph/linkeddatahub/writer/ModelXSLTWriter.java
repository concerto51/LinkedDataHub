/*
 * Copyright 2020 Martynas Jusevičius <martynas@atomgraph.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.atomgraph.linkeddatahub.writer;

import com.atomgraph.client.util.DataManager;
import com.atomgraph.linkeddatahub.apps.model.EndUserApplication;
import com.atomgraph.linkeddatahub.model.auth.Agent;
import com.atomgraph.linkeddatahub.server.io.ValidatingModelProvider;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.security.MessageDigest;
import jakarta.inject.Singleton;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import javax.xml.transform.TransformerException;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XsltExecutable;
import org.apache.http.HttpHeaders;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.RDFWriter;
import org.apache.jena.riot.SysRIOT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JAX-RS writer that renders RDF as HTML using XSLT stylesheet.
 * 
 * @author Martynas Jusevičius {@literal <martynas@atomgraph.com>}
 */
@Provider
@Singleton
@Produces({MediaType.TEXT_HTML + ";charset=UTF-8", MediaType.APPLICATION_XHTML_XML + ";charset=UTF-8"})
public class ModelXSLTWriter extends XSLTWriterBase implements MessageBodyWriter<Model>
{

    private static final Logger log = LoggerFactory.getLogger(ModelXSLTWriter.class);

    /**
     * Constructs XSLT writer.
     * 
     * @param xsltExec compiled XSLT stylesheet
     * @param ontModelSpec ontology specification
     * @param dataManager RDF data manager
     * @param messageDigest message digest
     */
    public ModelXSLTWriter(XsltExecutable xsltExec, OntModelSpec ontModelSpec, DataManager dataManager, MessageDigest messageDigest)
    {
        super(xsltExec, ontModelSpec, dataManager, messageDigest);
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType)
    {
        return Model.class.isAssignableFrom(type);
    }
    
    @Override
    public long getSize(Model model, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType)
    {
        return -1;
    }
    
    @Override
    public void writeTo(Model model, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> headerMap, OutputStream entityStream) throws IOException
    {
        // authenticated agents get a different HTML representation and therefore a different entity tag
        if (headerMap.containsKey(HttpHeaders.ETAG) && headerMap.getFirst(HttpHeaders.ETAG) instanceof EntityTag && getSecurityContext() != null && getSecurityContext().getUserPrincipal() instanceof Agent)
        {
            EntityTag eTag = (EntityTag)headerMap.getFirst(HttpHeaders.ETAG);
            BigInteger eTagHash = new BigInteger(eTag.getValue(), 16);
            Agent agent = (Agent)getSecurityContext().getUserPrincipal();
            eTagHash = eTagHash.add(BigInteger.valueOf(agent.hashCode()));
            headerMap.replace(HttpHeaders.ETAG, Arrays.asList(new EntityTag(eTagHash.toString(16))));
        }
        
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream())
        {
            Map<String, Object> properties = new HashMap<>() ;
            properties.put("allowBadURIs", "true"); // round-tripping RDF/POST with user input may contain invalid URIs
            org.apache.jena.sparql.util.Context cxt = new org.apache.jena.sparql.util.Context();
            cxt.set(SysRIOT.sysRdfWriterProperties, properties);
        
            RDFWriter.create().
                format(RDFFormat.RDFXML_PLAIN).
                context(cxt).
                source(processWrite(model)).
                output(baos);
            
            transform(baos, mediaType, headerMap, entityStream);
        }
        catch (TransformerException | SaxonApiException ex)
        {
            if (log.isErrorEnabled()) log.error("XSLT transformation failed", ex);
            throw new InternalServerErrorException(ex);
        }
    }

    /**
     * Hook for RDF model processing before write.
     * 
     * @param model RDF model
     * @return RDF model
     */
    public Model processWrite(Model model)
    {
        // show foaf:mbox in end-user apps
        if (getApplication().get().canAs(EndUserApplication.class)) return model;
        // show foaf:mbox for authenticated agents
        if (getSecurityContext() != null && getSecurityContext().getUserPrincipal() instanceof Agent) return model;

        // show foaf:mbox_sha1sum for all other agents (in admin apps)
        return ValidatingModelProvider.hashMboxes(getMessageDigest()).apply(model); // apply processing from superclasses
    }
    
}

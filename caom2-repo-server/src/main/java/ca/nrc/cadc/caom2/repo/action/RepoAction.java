/*
************************************************************************
*******************  CANADIAN ASTRONOMY DATA CENTRE  *******************
**************  CENTRE CANADIEN DE DONNÉES ASTRONOMIQUES  **************
*
*  (c) 2016.                            (c) 2016.
*  Government of Canada                 Gouvernement du Canada
*  National Research Council            Conseil national de recherches
*  Ottawa, Canada, K1A 0R6              Ottawa, Canada, K1A 0R6
*  All rights reserved                  Tous droits réservés
*
*  NRC disclaims any warranties,        Le CNRC dénie toute garantie
*  expressed, implied, or               énoncée, implicite ou légale,
*  statutory, of any kind with          de quelque nature que ce
*  respect to the software,             soit, concernant le logiciel,
*  including without limitation         y compris sans restriction
*  any warranty of merchantability      toute garantie de valeur
*  or fitness for a particular          marchande ou de pertinence
*  purpose. NRC shall not be            pour un usage particulier.
*  liable in any event for any          Le CNRC ne pourra en aucun cas
*  damages, whether direct or           être tenu responsable de tout
*  indirect, special or general,        dommage, direct ou indirect,
*  consequential or incidental,         particulier ou général,
*  arising from the use of the          accessoire ou fortuit, résultant
*  software.  Neither the name          de l'utilisation du logiciel. Ni
*  of the National Research             le nom du Conseil National de
*  Council of Canada nor the            Recherches du Canada ni les noms
*  names of its contributors may        de ses  participants ne peuvent
*  be used to endorse or promote        être utilisés pour approuver ou
*  products derived from this           promouvoir les produits dérivés
*  software without specific prior      de ce logiciel sans autorisation
*  written permission.                  préalable et particulière
*                                       par écrit.
*
*  This file is part of the             Ce fichier fait partie du projet
*  OpenCADC project.                    OpenCADC.
*
*  OpenCADC is free software:           OpenCADC est un logiciel libre ;
*  you can redistribute it and/or       vous pouvez le redistribuer ou le
*  modify it under the terms of         modifier suivant les termes de
*  the GNU Affero General Public        la “GNU Affero General Public
*  License as published by the          License” telle que publiée
*  Free Software Foundation,            par la Free Software Foundation
*  either version 3 of the              : soit la version 3 de cette
*  License, or (at your option)         licence, soit (à votre gré)
*  any later version.                   toute version ultérieure.
*
*  OpenCADC is distributed in the       OpenCADC est distribué
*  hope that it will be useful,         dans l’espoir qu’il vous
*  but WITHOUT ANY WARRANTY;            sera utile, mais SANS AUCUNE
*  without even the implied             GARANTIE : sans même la garantie
*  warranty of MERCHANTABILITY          implicite de COMMERCIALISABILITÉ
*  or FITNESS FOR A PARTICULAR          ni d’ADÉQUATION À UN OBJECTIF
*  PURPOSE.  See the GNU Affero         PARTICULIER. Consultez la Licence
*  General Public License for           Générale Publique GNU Affero
*  more details.                        pour plus de détails.
*
*  You should have received             Vous devriez avoir reçu une
*  a copy of the GNU Affero             copie de la Licence Générale
*  General Public License along         Publique GNU Affero avec
*  with OpenCADC.  If not, see          OpenCADC ; si ce n’est
*  <http://www.gnu.org/licenses/>.      pas le cas, consultez :
*                                       <http://www.gnu.org/licenses/>.
*
*  $Revision: 5 $
*
************************************************************************
*/

package ca.nrc.cadc.caom2.repo.action;

import ca.nrc.cadc.ac.GroupURI;
import java.io.IOException;
import java.net.URI;
import java.security.AccessControlException;
import java.security.cert.CertificateException;
import java.util.HashMap;
import java.util.Map;

import org.apache.log4j.Logger;

import ca.nrc.cadc.ac.UserNotFoundException;
import ca.nrc.cadc.ac.client.GMSClient;
import ca.nrc.cadc.caom2.Observation;
import ca.nrc.cadc.caom2.ObservationURI;
import ca.nrc.cadc.caom2.persistence.DatabaseObservationDAO;
import ca.nrc.cadc.caom2.persistence.ObservationDAO;
import ca.nrc.cadc.caom2.persistence.SQLGenerator;
import ca.nrc.cadc.caom2.repo.CaomRepoConfig;
import ca.nrc.cadc.cred.client.CredUtil;
import ca.nrc.cadc.net.ResourceNotFoundException;
import ca.nrc.cadc.rest.InlineContentHandler;
import ca.nrc.cadc.rest.RestAction;
import java.io.File;
import java.net.URISyntaxException;

/**
 *
 * @author pdowler
 */
public abstract class RepoAction extends RestAction
{
    private static final Logger log = Logger.getLogger(RepoAction.class);

    public static final String MODE_KEY = RepoAction.class.getName() + ".state";
    public static final String OFFLINE = "Offline";
    public static final String OFFLINE_MSG = "System is offline for maintainence";
    public static final String READ_ONLY = "ReadOnly";
    public static final String READ_ONLY_MSG = "System is in read-only mode for maintainence";
    public static final String READ_WRITE = "ReadWrite";
    private boolean readable = true;
    private boolean writable  = true;

    public static final String ERROR_MIMETYPE = "text/plain";

    private final GroupURI CADC_GROUP_URI  = new GroupURI("ivo://cadc.nrc.ca/gms?CADC");

    private String collection;
    protected ObservationURI uri;

    private transient CaomRepoConfig.Item repoConfig;
    private transient ObservationDAO dao;

    protected RepoAction() { }

    // this method will only downgrade the state to !readable and !writable
    // and will never restore them to true - that is intentional
    private void initState()
    {
        String key = RepoAction.MODE_KEY;
        String val = System.getProperty(key);
        if (OFFLINE.equals(val))
        {
            readable = false;
            writable = false;
        }
        else if (READ_ONLY.equals(val))
        {
            writable = false;
        }
    }

    private void initTarget()
    {
        if (collection == null)
        {
            String path = syncInput.getPath();
            if (path == null)
                throw new IllegalArgumentException("no collection specified");
            String[] parts = path.split("/");
            this.collection = parts[0];
            if (parts.length > 1)
            {
                String suri = "caom:" + path;
                try
                {
                    this.uri = new ObservationURI(new URI(suri));
                }
                catch(URISyntaxException | IllegalArgumentException ex)
                {
                    throw new IllegalArgumentException("invalid input: " + suri, ex);
                }
            }
        }
    }
    
    // return uri for get-observation, null for get-list, and throw for invalid
    protected ObservationURI getURI()
    {
        initTarget();
        return uri;
    }

    // return the specified collection or throw for invalid
    protected String getCollection()
    {
        initTarget();
        return collection;
    }

    protected ObservationDAO getDAO()
        throws IOException
    {
        if (dao == null)
            dao = getDAO(getCollection());
        return dao;
    }

    // read the input stream (POST and PUT) and extract the observation from the XML document
    protected Observation getInputObservation()
        throws IOException
    {
        /*
        // check content-type of input once we have a client that can set it
        List<String> types = syncInput.getHeaders("Content-Type");
        if (types.isEmpty())
            throw new IllegalArgumentException("no Content-Type found");
        String contentType = types.get(0);
        if (!CAOM_MIMETYPE.equalsIgnoreCase(contentType))
            throw new IllegalArgumentException("unexpected Content-Type found: " + contentType);
        */

    	Object obs = this.syncInput.getContent(ObservationInlineContentHandler.CONTENT_KEY);
    	if (obs != null)
    		return (Observation) obs;
    	else
    		return null;
    }

    /**
     * Check if the caller can read the specified resource.
     *
     * @param collection
     * @throws AccessControlException
     * @throws java.security.cert.CertificateException
     * @throws ca.nrc.cadc.net.ResourceNotFoundException
     * @throws java.io.IOException
     */
    protected void checkReadPermission(String collection)
        throws AccessControlException, CertificateException,
               ResourceNotFoundException, IOException
    {
        initState();
        if (!readable)
        {
            if (!writable)
                throw new IllegalStateException(OFFLINE_MSG);
            throw new IllegalStateException(READ_ONLY_MSG);
        }

        CaomRepoConfig.Item i = getConfig(collection);
        if (i == null)
            throw new ResourceNotFoundException("not found: " + uri);

        try
        {
            if ( CredUtil.checkCredentials() )
            {
                GroupURI grw = i.getReadWriteGroup();
                GMSClient gms = new GMSClient(grw.getServiceID());                
                if (gms.isMember(grw.getName()))
                	return;
                
                GroupURI gro = i.getReadOnlyGroup();
                if (!grw.getServiceID().equals(gro.getServiceID()))
                    gms = new GMSClient(gro.getServiceID());
                    
                if (gms.isMember(gro.getName()))
                	return;

                if (!gro.getServiceID().equals(CADC_GROUP_URI.getServiceID()))
                    gms = new GMSClient(CADC_GROUP_URI.getServiceID());
                if (gms.isMember(CADC_GROUP_URI.getName()))
                	return;
            }
        }
        catch(AccessControlException ex)
        {
            throw new AccessControlException("read permission denied (credentials not found): " + getURI());
        }
        catch(UserNotFoundException ex)
        {
            throw new AccessControlException("read permission denied (user not found): " + getURI());
        }
        throw new AccessControlException("permission denied: " + getURI());
    }

    /**
     * Check if the caller can create or modify the specified resource.
     *
     * @param uri
     * @throws AccessControlException
     * @throws java.security.cert.CertificateException
     * @throws ca.nrc.cadc.net.ResourceNotFoundException
     * @throws java.io.IOException
     */
    protected void checkWritePermission(ObservationURI uri)
        throws AccessControlException, CertificateException,
               ResourceNotFoundException, IOException
    {
        initState();
        if (!writable)
        {
            if (readable)
                throw new IllegalStateException(READ_ONLY_MSG);
            throw new IllegalStateException(OFFLINE_MSG);
        }

        CaomRepoConfig.Item i = getConfig(uri.getCollection());
        if (i == null)
            throw new ResourceNotFoundException(
                    "not found: " + uri);

        try
        {
            if ( CredUtil.checkCredentials() )
            {
                GroupURI guri = i.getReadWriteGroup();
                URI gmsURI = guri.getServiceID();
                GMSClient gms = new GMSClient(gmsURI);                
                if (gms.isMember(guri.getName()))
                    return;
            }
        }
        catch(AccessControlException ex)
        {
            throw new AccessControlException("read permission denied (credentials not found): " + getURI());
        }
        catch(UserNotFoundException ex)
        {
            throw new AccessControlException("read permission denied (user not found): " + getURI());
        }

        throw new AccessControlException("permission denied: " + getURI());
    }

    @Override
    protected InlineContentHandler getInlineContentHandler()
    {
    	return null;
    }

    // read configuration
    private CaomRepoConfig.Item getConfig(String collection)
        throws IOException
    {
        if (repoConfig != null)
            return repoConfig;
        
        String serviceName = syncInput.getContextPath();
        File config = new File(System.getProperty("user.home") + "/config", serviceName + ".properties");
        CaomRepoConfig rc = new CaomRepoConfig(config);
        if (rc.isEmpty())
            throw new IllegalStateException("no RepoConfig.Item(s)found");

        this.repoConfig = rc.getConfig(collection);
        return repoConfig;
    }

    // create DAO
    private ObservationDAO getDAO(String collection)
        throws IOException
    {
        CaomRepoConfig.Item i = getConfig(collection);
        if (i != null)
        {
            ObservationDAO ret = new DatabaseObservationDAO();
            Map<String,Object> props = new HashMap<String,Object>();
            props.put("jndiDataSourceName", i.getDataSourceName());
            props.put("database", i.getDatabase());
            props.put("schema", i.getSchema());
            props.put(SQLGenerator.class.getName(), i.getSqlGenerator());
            ret.setConfig(props);
            return ret;
        }
        throw new IllegalArgumentException("unknown collection: " + collection);
    }
}

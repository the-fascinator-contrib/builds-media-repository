import os

from au.edu.usq.fascinator.api.indexer import SearchRequest
from au.edu.usq.fascinator.common import JsonSimple
from au.edu.usq.fascinator.common.solr import SolrResult

from java.io import ByteArrayInputStream
from java.io import ByteArrayOutputStream
from java.io import UnsupportedEncodingException
from java.net import URLEncoder
from java.net import URLDecoder
from java.lang import Boolean

from java.util import ArrayList

class AtomData:
    def __init__(self):
        pass

    def __activate__(self, context):
        self.velocityContext = context
        self.services = context["Services"]
        self.request = context["request"]
        self.pageName = context["pageName"]
        self.formData = context["formData"]
        self.page = context["page"]
        self.portalId = context["portalId"]
        self.sessionState = context["sessionState"]
        self.portal = Services.getPortalManager().get(self.portalId)
        
        self.__searchField = self.formData.get("searchField", "full_text")
        
        self.__sortField = self.formData.get("sort-field")
        self.__sortOrder = self.formData.get("sort-order")
        if not (self.__sortField or self.__sortOrder):
            # use form data not specified, check session
            self.__sortField = self.sessionState.get("sortField", "score")
            self.__sortOrder = self.sessionState.get("sortOrder", "desc")
        self.sessionState.set("sortField", self.__sortField)
        self.sessionState.set("sortOrder", self.__sortOrder)
        self.__sortBy = "%s %s" % (self.__sortField, self.__sortOrder)
        
        self.__result = None
        self.__feed()

    # Get from velocity context
    def vc(self, index):
        if self.velocityContext[index] is not None:
            return self.velocityContext[index]
        else:
            log.error("ERROR: Requested context entry '" + index + "' doesn't exist")
            return None

    def __feed(self):
        requireEscape = False
        recordsPerPage = self.portal.recordsPerPage
        uri = URLDecoder.decode(self.request.getAttribute("RequestURI"))
        query = None
        pagePath = self.portal.getName() + "/" + self.pageName
        if query is None or query == "":
            query = self.formData.get("query")
            requireEscape = True
        if query is None or query == "":
            query = "*:*"
        
        if query == "*:*":
            self.__query = ""
        else:
            self.__query = query
            if requireEscape:
                query = self.__escapeQuery(query)
            query = "%s:%s" % (self.__searchField, query)
        self.sessionState.set("query", self.__query)
        
        # find objects with annotations matching the query
        if query != "*:*":
            anotarQuery = self.__query
            if requireEscape:
                anotarQuery = self.__escapeQuery(anotarQuery)
            annoReq = SearchRequest(anotarQuery)
            annoReq.setParam("facet", "false")
            annoReq.setParam("rows", str(99999))
            annoReq.setParam("sort", "dateCreated asc")
            annoReq.setParam("start", str(0))
            anotarOut = ByteArrayOutputStream()
            self.services.indexer.annotateSearch(annoReq, anotarOut)
            resultForAnotar = SolrResult(ByteArrayInputStream(anotarOut.toByteArray()))
            resultForAnotar = resultForAnotar.getResults()
            ids = HashSet()
            for annoDoc in resultForAnotar:
                annotatesUri = annoDoc.get("annotatesUri")
                ids.add(annotatesUri)
                print "Found annotation for %s" % annotatesUri
            # add annotation ids to query
            query += ' OR id:("' + '" OR "'.join(ids) + '")'
        
        portalSearchQuery = self.portal.searchQuery
        if portalSearchQuery == "":
            portalSearchQuery = query
        else:
            if query != "*:*":
                query += " AND " + portalSearchQuery
            else:
                query = portalSearchQuery
        
        req = SearchRequest(query)
        req.setParam("facet", "true")
        req.setParam("rows", str(recordsPerPage))
        req.setParam("facet.field", self.portal.facetFieldList)
        req.setParam("facet.sort", Boolean.toString(self.portal.getFacetSort()))
        req.setParam("facet.limit", str(self.portal.facetCount))
        req.setParam("sort", self.__sortBy)
        
        navUri = uri[len(pagePath):]
        self.__pageNum, fq, self.__fqParts = self.__parseUri(navUri)
        savedfq = self.sessionState.get("savedfq")
        limits = []
        if savedfq:
            limits.extend(savedfq)
        if fq:
            limits.extend(fq)
            self.sessionState.set("savedfq", limits)
            for q in fq:
                req.addParam("fq", URLDecoder.decode(q, "UTF-8"))
        
        portalQuery = self.portal.query
        if portalQuery:
            req.addParam("fq", portalQuery)
        req.addParam("fq", 'item_type:"object"')
        if req.getParams("fq"):
            self.__selected = ArrayList(req.getParams("fq"))
        
        # Make sure 'fq' has already been set in the session
        #if not self.page.authentication.is_admin():
        #    current_user = self.page.authentication.get_username()
        #    security_roles = self.page.authentication.get_roles_list()
        #    security_filter = 'security_filter:("' + '" OR "'.join(security_roles) + '")'
        #    security_exceptions = 'security_exception:"' + current_user + '"'
        #    owner_query = 'owner:"' + current_user + '"'
        #    security_query = "(" + security_filter + ") OR (" + security_exceptions + ") OR (" + owner_query + ")"
        #    req.addParam("fq", security_query)
        
        # Only show the publicly accessed object
        req.addParam("fq", 'security_filter("guest")')
        
        req.setParam("start", str((self.__pageNum - 1) * recordsPerPage))
        
        print " * search.py:", req.toString(), self.__pageNum
        
        out = ByteArrayOutputStream()
        self.services.indexer.search(req, out)
        self.__result = SolrResult(ByteArrayInputStream(out.toByteArray()))
#        recordsPerPage = self.portal.recordsPerPage
#        pageNum = self.sessionState.get("pageNum", 1)
#
#        query = "*:*"
#        if self.vc("formData").get("query"):
#            query = self.vc("formData").get("query")
#            query = self.__escapeQuery(query)
#
#        req = SearchRequest(query)
#        req.setParam("facet", "true")
#        req.setParam("rows", str(recordsPerPage))
#        req.setParam("facet.field", self.portal.facetFieldList)
#        req.setParam("facet.sort", "true")
#        req.setParam("facet.limit", str(self.portal.facetCount))
#        req.setParam("sort", "f_dc_title asc")
#
#        portalQuery = self.portal.query
#        if portalQuery:
#            req.addParam("fq", portalQuery)
#        else:
#            fq = self.sessionState.get("fq")
#            if fq is not None:
#                req.setParam("fq", fq)
#        req.addParam("fq", 'item_type:"object"')
#        req.setParam("start", str((pageNum - 1) * recordsPerPage))
#
#        print " * query: ", query
#        print " * portalQuery='%s'" % portalQuery
#        print " * feed.py:", req.toString()
#
#        out = ByteArrayOutputStream()
#        Services.indexer.search(req, out)
#        self.__result = SolrResult(ByteArrayInputStream(out.toByteArray()))

    def cleanUp(self, value):
        return value.replace("<", "&lt;").replace(">", "&gt;").replace("&", "&amp;")

    def hasResults(self): 
        return self.__result.numFound
        #return self.__result is not None

    def getResult(self):
        return self.__result 

    def getFileName(self, path):
        return os.path.split(path)[1]
    
    def getBaseUrl(self):
        return self.velocityContext["urlBase"]

    def __escapeQuery(self, q):
        eq = q
        # escape all solr/lucene special chars
        # from http://lucene.apache.org/java/2_4_0/queryparsersyntax.html#Escaping%20Special%20Characters
        for c in "+-&|!(){}[]^\"~*?:\\":
            eq = eq.replace(c, "\\%s" % c)
        ## Escape UTF8
        try:
            return URLEncoder.encode(eq, "UTF-8")
        except UnsupportedEncodingException, e:
            print "Error during UTF8 escape! ", repr(eq)
            return eq
    
    def __parseUri(self, uri):
        page = 1
        fq = []
        fqParts = []
        if uri != "":
            parts = uri.split("/")
            partType = None
            facetKey = None
            facetValues = None
            for part in parts:
                if partType == "page":
                    facetKey = None
                    page = int(part)
                elif partType == "category":
                    partType = "category-value"
                    facetValues = None
                    facetKey = part
                elif partType == "category-value":
                    if facetValues is None:
                        facetValues = []
                    if part in ["page", "category"]:
                        partType = part
                        facetQuery = '%s:"%s"' % (facetKey, "/".join(facetValues))
                        fq.append(facetQuery)
                        fqParts.append("category/%s/%s" % (facetKey, "/".join(facetValues)))
                        facetKey = None
                        facetValues = None
                    else:
                        facetValues.append(URLDecoder.decode(part))
                else:
                    partType = part
            if partType == "category-value":
                facetQuery = '%s:"%s"' % (facetKey, "/".join(facetValues))
                fq.append(facetQuery)
                fqParts.append("category/%s/%s" % (facetKey, "/".join(facetValues)))
        return page, fq, fqParts
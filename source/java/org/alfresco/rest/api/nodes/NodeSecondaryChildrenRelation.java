/*
 * Copyright (C) 2005-2016 Alfresco Software Limited.
 *
 * This file is part of Alfresco
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 */
package org.alfresco.rest.api.nodes;

import org.alfresco.model.ContentModel;
import org.alfresco.rest.api.model.AssocChild;
import org.alfresco.rest.api.model.Node;
import org.alfresco.rest.api.model.UserInfo;
import org.alfresco.rest.framework.WebApiDescription;
import org.alfresco.rest.framework.core.exceptions.ConstraintViolatedException;
import org.alfresco.rest.framework.core.exceptions.EntityNotFoundException;
import org.alfresco.rest.framework.core.exceptions.InvalidArgumentException;
import org.alfresco.rest.framework.resource.RelationshipResource;
import org.alfresco.rest.framework.resource.actions.interfaces.RelationshipResourceAction;
import org.alfresco.rest.framework.resource.parameters.CollectionWithPagingInfo;
import org.alfresco.rest.framework.resource.parameters.Paging;
import org.alfresco.rest.framework.resource.parameters.Parameters;
import org.alfresco.service.cmr.repository.AssociationExistsException;
import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.cmr.repository.DuplicateChildNodeNameException;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.alfresco.service.namespace.QNamePattern;
import org.alfresco.service.namespace.RegexQNamePattern;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Node Secondary Children
 *
 * Manage secondary child associations (sometimes also known as multi-filing)
 *
 * Please note, if you wish to manage primary child associations then please refer to other endpoints, for example:
 *
 * - to create primary child, use POST /nodes/{parentId}/children
 * - to delete node (ie. primary child), use DELETE /nodes/{nodeId}
 * - to move a node from one primary parent to another, use POST /nodes/{nodeId}/copy
 *
 * @author janv
 */
@RelationshipResource(name = "secondary-children",  entityResource = NodesEntityResource.class, title = "Node Secondary Children")
public class NodeSecondaryChildrenRelation extends AbstractNodeRelation implements
        RelationshipResourceAction.Read<Node>,
        RelationshipResourceAction.Create<AssocChild>,
        RelationshipResourceAction.Delete
{
    /**
     * List secondary children only
     *
     * @param parentNodeId String id of parent node
     */
    @Override
    @WebApiDescription(title = "Return a paged list of secondary child nodes based on child assocs")
    public CollectionWithPagingInfo<Node> readAll(String parentNodeId, Parameters parameters)
    {
        NodeRef parentNodeRef = nodes.validateOrLookupNode(parentNodeId, null);

        QNamePattern assocTypeQNameParam = getAssocTypeFromWhereElseAll(parameters);

        List<ChildAssociationRef> assocRefs = null;
        if (assocTypeQNameParam.equals(RegexQNamePattern.MATCH_ALL))
        {
            assocRefs = nodeService.getChildAssocs(parentNodeRef);
        }
        else
        {
            assocRefs = nodeService.getChildAssocs(parentNodeRef, assocTypeQNameParam, RegexQNamePattern.MATCH_ALL);
        }

        Map<QName, String> qnameMap = new HashMap<>(3);

        Map<String, UserInfo> mapUserInfo = new HashMap<>(10);

        List<String> includeParam = parameters.getInclude();

        List<Node> collection = new ArrayList<>(assocRefs.size());
        for (ChildAssociationRef assocRef : assocRefs)
        {
            if (! assocRef.isPrimary())
            {
                // minimal info by default (unless "include"d otherwise)
                Node node = nodes.getFolderOrDocument(assocRef.getChildRef(), null, null, includeParam, mapUserInfo);

                QName assocTypeQName = assocRef.getTypeQName();
                String assocType = qnameMap.get(assocTypeQName);
                if (assocType == null)
                {
                    assocType = assocTypeQName.toPrefixString(namespaceService);
                    qnameMap.put(assocTypeQName, assocType);
                }

                node.setAssociation(new AssocChild(assocType, assocRef.isPrimary()));

                collection.add(node);
            }
        }

        Paging paging = parameters.getPaging();
        return CollectionWithPagingInfo.asPaged(paging, collection, false, collection.size());
    }

    @Override
    @WebApiDescription(title="Add secondary child assoc")
    public List<AssocChild> create(String parentNodeId, List<AssocChild> entity, Parameters parameters)
    {
        List<AssocChild> result = new ArrayList<>(entity.size());

        NodeRef parentNodeRef = nodes.validateNode(parentNodeId);

        for (AssocChild assoc : entity)
        {
            QName assocTypeQName = getAssocType(assoc.getAssocType());

            try
            {
                NodeRef childNodeRef = nodes.validateNode(assoc.getChildId());

                String nodeName = (String)nodeService.getProperty(childNodeRef, ContentModel.PROP_NAME);
                QName assocChildQName = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, QName.createValidLocalName(nodeName));

                nodeService.addChild(parentNodeRef, childNodeRef, assocTypeQName, assocChildQName);
            }
            catch (AssociationExistsException aee)
            {
                throw new ConstraintViolatedException(aee.getMessage());
            }
            catch (DuplicateChildNodeNameException dcne)
            {
                throw new ConstraintViolatedException(dcne.getMessage());
            }

            result.add(assoc);
        }
        return result;
    }

    @Override
    @WebApiDescription(title = "Remove secondary child assoc(s)")
    public void delete(String parentNodeId, String childNodeId, Parameters parameters)
    {
        NodeRef parentNodeRef = nodes.validateNode(parentNodeId);
        NodeRef childNodeRef = nodes.validateNode(childNodeId);

        String assocTypeStr = parameters.getParameter(PARAM_ASSOC_TYPE);
        QName assocTypeQName = getAssocType(assocTypeStr, false, true);

        List<ChildAssociationRef> assocRefs = nodeService.getChildAssocs(parentNodeRef);

        boolean found = false;

        for (ChildAssociationRef assocRef : assocRefs)
        {
            if (! assocRef.getChildRef().equals(childNodeRef))
            {
                continue;
            }

            if (assocTypeQName != null)
            {
                if (assocTypeQName.equals(assocRef.getTypeQName()))
                {
                    if (assocRef.isPrimary())
                    {
                        throw new InvalidArgumentException("Cannot use secondary-children to delete primary assoc: "
                            +parentNodeId+","+assocTypeStr+","+childNodeId);
                    }

                    boolean existed = nodeService.removeSecondaryChildAssociation(assocRef);
                    if (existed)
                    {
                        found = true;
                    }
                }
            }
            else
            {
                if (! assocRef.isPrimary())
                {
                    boolean existed = nodeService.removeSecondaryChildAssociation(assocRef);
                    if (existed)
                    {
                        found = true;
                    }
                }
            }
        }

        if (! found)
        {
            throw new EntityNotFoundException(parentNodeId+","+assocTypeStr+","+childNodeId);
        }
    }
}

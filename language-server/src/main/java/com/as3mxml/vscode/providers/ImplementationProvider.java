/*
Copyright 2016-2019 Bowler Hat LLC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.as3mxml.vscode.providers;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import com.as3mxml.vscode.project.ILspProject;
import com.as3mxml.vscode.project.WorkspaceFolderData;
import com.as3mxml.vscode.utils.CompilationUnitUtils.IncludeFileData;
import com.as3mxml.vscode.utils.DefinitionUtils;
import com.as3mxml.vscode.utils.FileTracker;
import com.as3mxml.vscode.utils.LanguageServerCompilerUtils;
import com.as3mxml.vscode.utils.MXMLDataUtils;
import com.as3mxml.vscode.utils.WorkspaceFolderManager;

import org.apache.royale.compiler.definitions.IClassDefinition;
import org.apache.royale.compiler.definitions.IDefinition;
import org.apache.royale.compiler.definitions.IInterfaceDefinition;
import org.apache.royale.compiler.internal.mxml.MXMLData;
import org.apache.royale.compiler.mxml.IMXMLTagData;
import org.apache.royale.compiler.tree.as.IASNode;
import org.apache.royale.compiler.tree.as.IIdentifierNode;
import org.apache.royale.compiler.units.ICompilationUnit;
import org.apache.royale.compiler.units.ICompilationUnit.UnitType;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

public class ImplementationProvider
{
    private WorkspaceFolderManager workspaceFolderManager;
    private FileTracker fileTracker;

	public ImplementationProvider(WorkspaceFolderManager workspaceFolderManager, FileTracker fileTracker)
	{
        this.workspaceFolderManager = workspaceFolderManager;
        this.fileTracker = fileTracker;
	}

	public Either<List<? extends Location>, List<? extends LocationLink>> implementation(TextDocumentPositionParams params, CancelChecker cancelToken)
	{
		cancelToken.checkCanceled();
		TextDocumentIdentifier textDocument = params.getTextDocument();
		Position position = params.getPosition();
		Path path = LanguageServerCompilerUtils.getPathFromLanguageServerURI(textDocument.getUri());
		if (path == null)
		{
			cancelToken.checkCanceled();
			return Either.forLeft(Collections.emptyList());
		}
		WorkspaceFolderData folderData = workspaceFolderManager.getWorkspaceFolderDataForSourceFile(path);
		if(folderData == null || folderData.project == null)
		{
			cancelToken.checkCanceled();
			return Either.forLeft(Collections.emptyList());
		}
		ILspProject project = folderData.project;

        IncludeFileData includeFileData = folderData.includedFiles.get(path.toString());
		int currentOffset = LanguageServerCompilerUtils.getOffsetFromPosition(fileTracker.getReader(path), position, includeFileData);
		if (currentOffset == -1)
		{
			cancelToken.checkCanceled();
			return Either.forLeft(Collections.emptyList());
		}
		MXMLData mxmlData = workspaceFolderManager.getMXMLDataForPath(path, folderData);

		IMXMLTagData offsetTag = MXMLDataUtils.getOffsetMXMLTag(mxmlData, currentOffset);
		if (offsetTag != null)
		{
			IASNode embeddedNode = workspaceFolderManager.getEmbeddedActionScriptNodeInMXMLTag(offsetTag, path, currentOffset, folderData);
			if (embeddedNode != null)
			{
				List<? extends Location> result = actionScriptImplementation(embeddedNode, project);
				cancelToken.checkCanceled();
				return Either.forLeft(result);
			}
		}
		IASNode offsetNode = workspaceFolderManager.getOffsetNode(path, currentOffset, folderData);
		List<? extends Location> result = actionScriptImplementation(offsetNode, project);
		cancelToken.checkCanceled();
		return Either.forLeft(result);
	}

    private List<? extends Location> actionScriptImplementation(IASNode offsetNode, ILspProject project)
    {
        if (offsetNode == null)
        {
            //we couldn't find a node at the specified location
            return Collections.emptyList();
        }

        IInterfaceDefinition interfaceDefinition = null;

        if (offsetNode instanceof IIdentifierNode)
        {
            IIdentifierNode expressionNode = (IIdentifierNode) offsetNode;
            IDefinition resolvedDefinition = expressionNode.resolve(project);
            if (resolvedDefinition instanceof IInterfaceDefinition)
            {
                interfaceDefinition = (IInterfaceDefinition) resolvedDefinition;
            }
        }

        if (interfaceDefinition == null)
        {
            //VSCode may call typeDefinition() when there isn't necessarily a
            //type definition referenced at the current position.
            return Collections.emptyList();
        }
        
        List<Location> result = new ArrayList<>();
        for (ICompilationUnit unit : project.getCompilationUnits())
        {
            if (unit == null)
            {
                continue;
            }
            UnitType unitType = unit.getCompilationUnitType();
            if (!UnitType.AS_UNIT.equals(unitType) && !UnitType.MXML_UNIT.equals(unitType))
            {
                continue;
            }
            Collection<IDefinition> definitions = null;
            try
            {
                definitions = unit.getFileScopeRequest().get().getExternallyVisibleDefinitions();
            }
            catch (Exception e)
            {
                //safe to ignore
                continue;
            }

            for (IDefinition definition : definitions)
            {
                if (!(definition instanceof IClassDefinition))
                {
                    continue;
                }
                IClassDefinition classDefinition = (IClassDefinition) definition;
                if (DefinitionUtils.isImplementationOfInterface(classDefinition, interfaceDefinition, project))
                {
                    Location location = workspaceFolderManager.getLocationFromDefinition(classDefinition, project);
                    if (location != null)
                    {
                        result.add(location);
                    }
                }
            }
        }
        return result;
    }
}
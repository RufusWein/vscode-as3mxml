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

import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.as3mxml.vscode.commands.ICommandConstants;
import com.as3mxml.vscode.project.ILspProject;
import com.as3mxml.vscode.project.WorkspaceFolderData;
import com.as3mxml.vscode.utils.ASTUtils;
import com.as3mxml.vscode.utils.CodeActionsUtils;
import com.as3mxml.vscode.utils.CompilationUnitUtils.IncludeFileData;
import com.as3mxml.vscode.utils.CompilerProjectUtils;
import com.as3mxml.vscode.utils.FileTracker;
import com.as3mxml.vscode.utils.ImportRange;
import com.as3mxml.vscode.utils.LanguageServerCompilerUtils;
import com.as3mxml.vscode.utils.MXMLDataUtils;
import com.as3mxml.vscode.utils.SourcePathUtils;
import com.as3mxml.vscode.utils.WorkspaceFolderManager;
import com.google.common.collect.Lists;
import com.google.gson.JsonObject;

import org.apache.royale.compiler.definitions.IDefinition;
import org.apache.royale.compiler.definitions.IInterfaceDefinition;
import org.apache.royale.compiler.internal.mxml.MXMLData;
import org.apache.royale.compiler.mxml.IMXMLTagData;
import org.apache.royale.compiler.tree.as.IASNode;
import org.apache.royale.compiler.tree.as.IClassNode;
import org.apache.royale.compiler.tree.as.IContainerNode;
import org.apache.royale.compiler.tree.as.IExpressionNode;
import org.apache.royale.compiler.tree.as.IFunctionCallNode;
import org.apache.royale.compiler.tree.as.IIdentifierNode;
import org.apache.royale.compiler.tree.as.ILanguageIdentifierNode;
import org.apache.royale.compiler.tree.as.IMemberAccessExpressionNode;
import org.apache.royale.compiler.tree.as.ITryNode;
import org.apache.royale.compiler.tree.mxml.IMXMLInstanceNode;
import org.apache.royale.compiler.units.ICompilationUnit;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

public class CodeActionProvider
{
    private static final String MXML_EXTENSION = ".mxml";

    private WorkspaceFolderManager workspaceFolderManager;
    private FileTracker fileTracker;

	public CodeActionProvider(WorkspaceFolderManager workspaceFolderManager, FileTracker fileTracker)
	{
        this.workspaceFolderManager = workspaceFolderManager;
        this.fileTracker = fileTracker;
	}

	public List<Either<Command, CodeAction>> codeAction(CodeActionParams params, CancelChecker cancelToken)
	{
		cancelToken.checkCanceled();
		TextDocumentIdentifier textDocument = params.getTextDocument();
		Path path = LanguageServerCompilerUtils.getPathFromLanguageServerURI(textDocument.getUri());
		if (path == null)
		{
			cancelToken.checkCanceled();
			return Collections.emptyList();
		}
		//we don't need to create code actions for non-open files
		if (!fileTracker.isOpen(path))
		{
			cancelToken.checkCanceled();
			return Collections.emptyList();
		}
		WorkspaceFolderData folderData = workspaceFolderManager.getWorkspaceFolderDataForSourceFile(path);
		if (folderData == null || folderData.project == null
                || folderData.equals(workspaceFolderManager.getFallbackFolderData()))
		{
			cancelToken.checkCanceled();
			//the path must be in the workspace or source-path
			return Collections.emptyList();
		}
		ILspProject project = folderData.project;

		if (project == null || !SourcePathUtils.isInProjectSourcePath(path, project, folderData.configurator))
		{
			cancelToken.checkCanceled();
			//the path must be in the workspace or source-path
			return Collections.emptyList();
		}

		List<? extends Diagnostic> diagnostics = params.getContext().getDiagnostics();
		List<Either<Command, CodeAction>> codeActions = new ArrayList<>();
		findSourceActions(path, codeActions);
		findCodeActionsForDiagnostics(path, folderData, diagnostics, codeActions);

		ICompilationUnit unit = CompilerProjectUtils.findCompilationUnit(path, project);
		if (unit != null)
		{
			IASNode ast = ASTUtils.getCompilationUnitAST(unit);
			if (ast != null)
			{
				String fileText = fileTracker.getText(path);
				CodeActionsUtils.findGetSetCodeActions(ast, project, textDocument.getUri(), fileText, params.getRange(), codeActions);
			}
		}
		cancelToken.checkCanceled();
		return codeActions;
	}

    private void findSourceActions(Path path, List<Either<Command, CodeAction>> codeActions)
    {
        Command organizeCommand = new Command();
        organizeCommand.setTitle("Organize Imports");
        organizeCommand.setCommand(ICommandConstants.ORGANIZE_IMPORTS_IN_URI);
        JsonObject uri = new JsonObject();
        uri.addProperty("external", path.toUri().toString());
        organizeCommand.setArguments(Lists.newArrayList(
            uri
        ));
        CodeAction organizeImports = new CodeAction();
        organizeImports.setKind(CodeActionKind.SourceOrganizeImports);
        organizeImports.setTitle(organizeCommand.getTitle());
        organizeImports.setCommand(organizeCommand);
        codeActions.add(Either.forRight(organizeImports));
    }

    private void findCodeActionsForDiagnostics(Path path, WorkspaceFolderData folderData, List<? extends Diagnostic> diagnostics, List<Either<Command, CodeAction>> codeActions)
    {
        boolean handledUnimplementedMethods = false;
        for (Diagnostic diagnostic : diagnostics)
        {
            //I don't know why this can be null
            String code = diagnostic.getCode();
            if (code == null)
            {
                continue;
            }
            switch (code)
            {
                case "1120": //AccessUndefinedPropertyProblem
                {
                    //see if there's anything we can import
                    createCodeActionsForImport(path, diagnostic, folderData, codeActions);
                    createCodeActionForMissingLocalVariable(path, diagnostic, folderData, codeActions);
                    createCodeActionForMissingField(path, diagnostic, folderData, codeActions);
                    createCodeActionForMissingEventListener(path, diagnostic, folderData, codeActions);
                    break;
                }
                case "1046": //UnknownTypeProblem
                {
                    //see if there's anything we can import
                    createCodeActionsForImport(path, diagnostic, folderData, codeActions);
                    break;
                }
                case "1017": //UnknownSuperclassProblem
                {
                    //see if there's anything we can import
                    createCodeActionsForImport(path, diagnostic, folderData, codeActions);
                    break;
                }
                case "1045": //UnknownInterfaceProblem
                {
                    //see if there's anything we can import
                    createCodeActionsForImport(path, diagnostic, folderData, codeActions);
                    break;
                }
                case "1061": //StrictUndefinedMethodProblem
                {
                    createCodeActionForMissingMethod(path, diagnostic, folderData, codeActions);
                    break;
                }
                case "1073": //MissingCatchOrFinallyProblem
                {
                    createCodeActionForMissingCatchOrFinally(path, diagnostic, folderData, codeActions);
                    break;
                }
                case "1119": //AccessUndefinedMemberProblem
                {
                    createCodeActionForMissingField(path, diagnostic, folderData, codeActions);
                    createCodeActionForMissingEventListener(path, diagnostic, folderData, codeActions);
                    break;
                }
                case "1178": //InaccessiblePropertyReferenceProblem
                {
                    //see if there's anything we can import
                    createCodeActionsForImport(path, diagnostic, folderData, codeActions);
                    break;
                }
                case "1180": //CallUndefinedMethodProblem
                {
                    //see if there's anything we can import
                    createCodeActionsForImport(path, diagnostic, folderData, codeActions);
                    createCodeActionForMissingMethod(path, diagnostic, folderData, codeActions);
                    break;
                }
                case "1044": //UnimplementedInterfaceMethodProblem
                {
                    //only needs to be handled one time
                    if(!handledUnimplementedMethods)
                    {
                        handledUnimplementedMethods = true;
                        createCodeActionForUnimplementedMethods(path, diagnostic, folderData, codeActions);
                    }
                    break;
                }
                case "as3mxml-unused-import":
                {
                    createCodeActionsForUnusedImport(path, diagnostic, folderData, codeActions);
                }
            }
        }
    }

    private void createCodeActionForMissingField(Path path, Diagnostic diagnostic, WorkspaceFolderData folderData, List<Either<Command, CodeAction>> codeActions)
    {
        Position position = diagnostic.getRange().getStart();
        IncludeFileData includeFileData = folderData.includedFiles.get(path.toString());
		int currentOffset = LanguageServerCompilerUtils.getOffsetFromPosition(fileTracker.getReader(path), position, includeFileData);
        IASNode offsetNode = workspaceFolderManager.getOffsetNode(path, currentOffset, folderData);
        if (offsetNode instanceof IMXMLInstanceNode)
        {
            MXMLData mxmlData = workspaceFolderManager.getMXMLDataForPath(path, folderData);
            if (mxmlData != null)
            {
                IMXMLTagData offsetTag = MXMLDataUtils.getOffsetMXMLTag(mxmlData, currentOffset);
                if(offsetTag != null)
                {
                    //workaround for bug in Royale compiler
                    Position newPosition = new Position(position.getLine(), position.getCharacter() + 1);
                    int newOffset = LanguageServerCompilerUtils.getOffsetFromPosition(fileTracker.getReader(path), newPosition, includeFileData);
                    offsetNode = workspaceFolderManager.getEmbeddedActionScriptNodeInMXMLTag(offsetTag, path, newOffset, folderData);
                }
            }
        }
        IIdentifierNode identifierNode = null;
        if (offsetNode instanceof IIdentifierNode)
        {
            IASNode parentNode = offsetNode.getParent();
            if (parentNode instanceof IMemberAccessExpressionNode)
            {
                IMemberAccessExpressionNode memberAccessExpressionNode = (IMemberAccessExpressionNode) offsetNode.getParent();
                IExpressionNode leftOperandNode = memberAccessExpressionNode.getLeftOperandNode();
                if (leftOperandNode instanceof ILanguageIdentifierNode)
                {
                    ILanguageIdentifierNode leftIdentifierNode = (ILanguageIdentifierNode) leftOperandNode;
                    if (leftIdentifierNode.getKind() == ILanguageIdentifierNode.LanguageIdentifierKind.THIS)
                    {
                        identifierNode = (IIdentifierNode) offsetNode;
                    }
                }
            }
            else //no member access
            {
                identifierNode = (IIdentifierNode) offsetNode;
            }
        }
        if (identifierNode == null)
        {
            return;
        }
        String fileText = fileTracker.getText(path);
        if(fileText == null)
        {
            return;
        }
        WorkspaceEdit edit = CodeActionsUtils.createWorkspaceEditForGenerateFieldVariable(
            identifierNode, path.toUri().toString(), fileText);
        if(edit == null)
        {
            return;
        }
        
        CodeAction codeAction = new CodeAction();
        codeAction.setDiagnostics(Collections.singletonList(diagnostic));
        codeAction.setTitle("Generate Field Variable");
        codeAction.setEdit(edit);
        codeAction.setKind(CodeActionKind.QuickFix);
        codeActions.add(Either.forRight(codeAction));
    }
    
    private void createCodeActionForMissingLocalVariable(Path path, Diagnostic diagnostic, WorkspaceFolderData folderData, List<Either<Command, CodeAction>> codeActions)
    {
        Position position = diagnostic.getRange().getStart();
        IncludeFileData includeFileData = folderData.includedFiles.get(path.toString());
		int currentOffset = LanguageServerCompilerUtils.getOffsetFromPosition(fileTracker.getReader(path), position, includeFileData);
        IASNode offsetNode = workspaceFolderManager.getOffsetNode(path, currentOffset, folderData);
        if (offsetNode instanceof IMXMLInstanceNode)
        {
            MXMLData mxmlData = workspaceFolderManager.getMXMLDataForPath(path, folderData);
            if (mxmlData != null)
            {
                IMXMLTagData offsetTag = MXMLDataUtils.getOffsetMXMLTag(mxmlData, currentOffset);
                if(offsetTag != null)
                {
                    //workaround for bug in Royale compiler
                    Position newPosition = new Position(position.getLine(), position.getCharacter() + 1);
                    int newOffset = LanguageServerCompilerUtils.getOffsetFromPosition(fileTracker.getReader(path), newPosition, includeFileData);
                    offsetNode = workspaceFolderManager.getEmbeddedActionScriptNodeInMXMLTag(offsetTag, path, newOffset, folderData);
                }
            }
        }
        IIdentifierNode identifierNode = null;
        if (offsetNode instanceof IIdentifierNode)
        {
            identifierNode = (IIdentifierNode) offsetNode;
        }
        if (identifierNode == null)
        {
            return;
        }
        String fileText = fileTracker.getText(path);
        if(fileText == null)
        {
            return;
        }

        WorkspaceEdit edit = CodeActionsUtils.createWorkspaceEditForGenerateLocalVariable(
            identifierNode, path.toUri().toString(), fileText);
        if(edit == null)
        {
            return;
        }

        CodeAction codeAction = new CodeAction();
        codeAction.setDiagnostics(Collections.singletonList(diagnostic));
        codeAction.setTitle("Generate Local Variable");
        codeAction.setEdit(edit);
        codeAction.setKind(CodeActionKind.QuickFix);
        codeActions.add(Either.forRight(codeAction));
    }

    private void createCodeActionForMissingCatchOrFinally(Path path, Diagnostic diagnostic, WorkspaceFolderData folderData, List<Either<Command, CodeAction>> codeActions)
    {
        ILspProject project = folderData.project;
        Position position = diagnostic.getRange().getStart();
        IncludeFileData includeFileData = folderData.includedFiles.get(path.toString());
		int currentOffset = LanguageServerCompilerUtils.getOffsetFromPosition(fileTracker.getReader(path), position, includeFileData);
        IASNode offsetNode = workspaceFolderManager.getOffsetNode(path, currentOffset, folderData);
        if(!(offsetNode instanceof ITryNode))
        {
            return;
        }
        ITryNode tryNode = (ITryNode) offsetNode;
        String fileText = fileTracker.getText(path);
        if(fileText == null)
        {
            return;
        }

        WorkspaceEdit edit = CodeActionsUtils.createWorkspaceEditForGenerateCatch(
            tryNode, path.toUri().toString(), fileText, project);
        if(edit == null)
        {
            return;
        }

        CodeAction codeAction = new CodeAction();
        codeAction.setDiagnostics(Collections.singletonList(diagnostic));
        codeAction.setTitle("Generate catch");
        codeAction.setEdit(edit);
        codeAction.setKind(CodeActionKind.QuickFix);
        codeActions.add(Either.forRight(codeAction));
    }

    private void createCodeActionForUnimplementedMethods(Path path, Diagnostic diagnostic, WorkspaceFolderData folderData, List<Either<Command, CodeAction>> codeActions)
    {
        ILspProject project = folderData.project;
        Position position = diagnostic.getRange().getStart();
        IncludeFileData includeFileData = folderData.includedFiles.get(path.toString());
		int currentOffset = LanguageServerCompilerUtils.getOffsetFromPosition(fileTracker.getReader(path), position, includeFileData);
        IASNode offsetNode = workspaceFolderManager.getOffsetNode(path, currentOffset, folderData);
        if (offsetNode == null)
        {
            return;
        }

        IClassNode classNode = (IClassNode) offsetNode.getAncestorOfType(IClassNode.class);
        if (classNode == null)
        {
            return;
        }

        String fileText = fileTracker.getText(path);
        if(fileText == null)
        {
            return;
        }

        for (IExpressionNode exprNode : classNode.getImplementedInterfaceNodes())
        {
            IInterfaceDefinition interfaceDefinition = (IInterfaceDefinition) exprNode.resolve(project);
            if (interfaceDefinition == null)
            {
                continue;
            }
            WorkspaceEdit edit = CodeActionsUtils.createWorkspaceEditForImplementInterface(
                classNode, interfaceDefinition, path.toUri().toString(), fileText, project);
            if (edit == null)
            {
                continue;
            }

            CodeAction codeAction = new CodeAction();
            codeAction.setDiagnostics(Collections.singletonList(diagnostic));
            codeAction.setTitle("Implement interface '" + interfaceDefinition.getBaseName() + "'");
            codeAction.setEdit(edit);
            codeAction.setKind(CodeActionKind.QuickFix);
            codeActions.add(Either.forRight(codeAction));
        }
    }

    private void createCodeActionForMissingMethod(Path path, Diagnostic diagnostic, WorkspaceFolderData folderData, List<Either<Command, CodeAction>> codeActions)
    {
        ILspProject project = folderData.project;
        Position position = diagnostic.getRange().getStart();
        IncludeFileData includeFileData = folderData.includedFiles.get(path.toString());
		int currentOffset = LanguageServerCompilerUtils.getOffsetFromPosition(fileTracker.getReader(path), position, includeFileData);
        IASNode offsetNode = workspaceFolderManager.getOffsetNode(path, currentOffset, folderData);
        if (offsetNode == null)
        {
            return;
        }
        if (offsetNode instanceof IMXMLInstanceNode)
        {
            MXMLData mxmlData = workspaceFolderManager.getMXMLDataForPath(path, folderData);
            if (mxmlData != null)
            {
                IMXMLTagData offsetTag = MXMLDataUtils.getOffsetMXMLTag(mxmlData, currentOffset);
                if(offsetTag != null)
                {
                    //workaround for bug in Royale compiler
                    Position newPosition = new Position(position.getLine(), position.getCharacter() + 1);
                    int newOffset = LanguageServerCompilerUtils.getOffsetFromPosition(fileTracker.getReader(path), newPosition, includeFileData);
                    offsetNode = workspaceFolderManager.getEmbeddedActionScriptNodeInMXMLTag(offsetTag, path, newOffset, folderData);
                }
            }
        }
        IASNode parentNode = offsetNode.getParent();

        IFunctionCallNode functionCallNode = null;
        if (offsetNode instanceof IFunctionCallNode)
        {
            functionCallNode = (IFunctionCallNode) offsetNode;
        }
        else if (parentNode instanceof IFunctionCallNode)
        {
            functionCallNode = (IFunctionCallNode) offsetNode.getParent();
        }
        else if(offsetNode instanceof IIdentifierNode
                && parentNode instanceof IMemberAccessExpressionNode)
        {
            IASNode gpNode = parentNode.getParent();
            if (gpNode instanceof IFunctionCallNode)
            {
                functionCallNode = (IFunctionCallNode) gpNode;
            }
        }
        if (functionCallNode == null)
        {
            return;
        }
        String fileText = fileTracker.getText(path);
        if(fileText == null)
        {
            return;
        }

        WorkspaceEdit edit = CodeActionsUtils.createWorkspaceEditForGenerateMethod(
            functionCallNode, path.toUri().toString(), fileText, project);
        if(edit == null)
        {
            return;
        }

        CodeAction codeAction = new CodeAction();
        codeAction.setDiagnostics(Collections.singletonList(diagnostic));
        codeAction.setTitle("Generate Method");
        codeAction.setEdit(edit);
        codeAction.setKind(CodeActionKind.QuickFix);
        codeActions.add(Either.forRight(codeAction));
    }

    private void createCodeActionForMissingEventListener(Path path, Diagnostic diagnostic, WorkspaceFolderData folderData, List<Either<Command, CodeAction>> codeActions)
    {
        ILspProject project = folderData.project;
        Position position = diagnostic.getRange().getStart();
        IncludeFileData includeFileData = folderData.includedFiles.get(path.toString());
		int currentOffset = LanguageServerCompilerUtils.getOffsetFromPosition(fileTracker.getReader(path), position, includeFileData);
        IASNode offsetNode = workspaceFolderManager.getOffsetNode(path, currentOffset, folderData);
        if (offsetNode == null)
        {
            return;
        }
        if (offsetNode instanceof IMXMLInstanceNode)
        {
            MXMLData mxmlData = workspaceFolderManager.getMXMLDataForPath(path, folderData);
            if (mxmlData != null)
            {
                IMXMLTagData offsetTag = MXMLDataUtils.getOffsetMXMLTag(mxmlData, currentOffset);
                if(offsetTag != null)
                {
                    //workaround for bug in Royale compiler
                    Position newPosition = new Position(position.getLine(), position.getCharacter() + 1);
                    int newOffset = LanguageServerCompilerUtils.getOffsetFromPosition(fileTracker.getReader(path), newPosition, includeFileData);
                    offsetNode = workspaceFolderManager.getEmbeddedActionScriptNodeInMXMLTag(offsetTag, path, newOffset, folderData);
                }
            }
        }
        if (!(offsetNode instanceof IIdentifierNode))
        {
            return;
        }
        IASNode parentNode = offsetNode.getParent();
        if (parentNode instanceof IMemberAccessExpressionNode)
        {
            IMemberAccessExpressionNode memberAccessExpressionNode = (IMemberAccessExpressionNode) parentNode;
            IExpressionNode leftOperandNode = memberAccessExpressionNode.getLeftOperandNode();
            IExpressionNode rightOperandNode = memberAccessExpressionNode.getRightOperandNode();
            if (rightOperandNode instanceof IIdentifierNode
                    && leftOperandNode instanceof ILanguageIdentifierNode)
            {
                ILanguageIdentifierNode leftIdentifierNode = (ILanguageIdentifierNode) leftOperandNode;
                if (leftIdentifierNode.getKind() == ILanguageIdentifierNode.LanguageIdentifierKind.THIS)
                {
                    parentNode = parentNode.getParent();
                }
            }
        }
        if (!(parentNode instanceof IContainerNode))
        {
            return;
        }

        IASNode gpNode = parentNode.getParent();
        if (!(gpNode instanceof IFunctionCallNode))
        {
            return;
        }

        IFunctionCallNode functionCallNode = (IFunctionCallNode) gpNode;
        if(!ASTUtils.isFunctionCallWithName(functionCallNode, "addEventListener"))
        {
            return;
        }

        IExpressionNode[] args = functionCallNode.getArgumentNodes();
        if (args.length < 2 || (args[1] != offsetNode && args[1] != offsetNode.getParent()))
        {
            return;
        }

        String eventTypeClassName = ASTUtils.findEventClassNameFromAddEventListenerFunctionCall(functionCallNode, project);
        if (eventTypeClassName == null)
        {
            return;
        }

        IIdentifierNode functionIdentifier = (IIdentifierNode) offsetNode;
        String functionName = functionIdentifier.getName();
        if (functionName.length() == 0)
        {
            return;
        }

        String fileText = fileTracker.getText(path);
        if(fileText == null)
        {
            return;
        }

        WorkspaceEdit edit = CodeActionsUtils.createWorkspaceEditForGenerateEventListener(
            functionIdentifier, functionName, eventTypeClassName,
            path.toUri().toString(), fileText, project);
        if(edit == null)
        {
            return;
        }

        CodeAction codeAction = new CodeAction();
        codeAction.setDiagnostics(Collections.singletonList(diagnostic));
        codeAction.setTitle("Generate Event Listener");
        codeAction.setEdit(edit);
        codeAction.setKind(CodeActionKind.QuickFix);
        codeActions.add(Either.forRight(codeAction));
    }

    private void createCodeActionsForImport(Path path, Diagnostic diagnostic, WorkspaceFolderData folderData, List<Either<Command, CodeAction>> codeActions)
    {
        ILspProject project = folderData.project;
        Position position = diagnostic.getRange().getStart();
        IncludeFileData includeFileData = folderData.includedFiles.get(path.toString());
		int currentOffset = LanguageServerCompilerUtils.getOffsetFromPosition(fileTracker.getReader(path), position, includeFileData);
        IASNode offsetNode = workspaceFolderManager.getOffsetNode(path, currentOffset, folderData);
        IMXMLTagData offsetTag = null;
        boolean isMXML = path.toUri().toString().endsWith(MXML_EXTENSION);
        if (isMXML)
        {
            MXMLData mxmlData = workspaceFolderManager.getMXMLDataForPath(path, folderData);
            if (mxmlData != null)
            {
                offsetTag = MXMLDataUtils.getOffsetMXMLTag(mxmlData, currentOffset);
            }
        }
        if (offsetNode instanceof IMXMLInstanceNode && offsetTag != null)
        {
            //workaround for bug in Royale compiler
            Position newPosition = new Position(position.getLine(), position.getCharacter() + 1);
            int newOffset = LanguageServerCompilerUtils.getOffsetFromPosition(fileTracker.getReader(path), newPosition, includeFileData);
            offsetNode = workspaceFolderManager.getEmbeddedActionScriptNodeInMXMLTag(offsetTag, path, newOffset, folderData);
        }
        if (offsetNode == null || !(offsetNode instanceof IIdentifierNode))
        {
            return;
        }
        ImportRange importRange = null;
        if (offsetTag != null)
        {
            importRange = ImportRange.fromOffsetTag(offsetTag, currentOffset);
        }
        else
        {
            importRange = ImportRange.fromOffsetNode(offsetNode);
        }
        String uri = importRange.uri;
        String fileText = fileTracker.getText(path);
        if(fileText == null)
        {
            return;
        }

        IIdentifierNode identifierNode = (IIdentifierNode) offsetNode;
        String typeString = identifierNode.getName();

        List<IDefinition> types = ASTUtils.findTypesThatMatchName(typeString, project.getCompilationUnits());
        for (IDefinition definitionToImport : types)
        {
            WorkspaceEdit edit = CodeActionsUtils.createWorkspaceEditForAddImport(definitionToImport, fileText, uri, importRange);
            if (edit == null)
            {
                continue;
            }
            CodeAction codeAction = new CodeAction();
            codeAction.setTitle("Import " + definitionToImport.getQualifiedName());
            codeAction.setEdit(edit);
            codeAction.setKind(CodeActionKind.QuickFix);
            codeAction.setDiagnostics(Collections.singletonList(diagnostic));
            codeActions.add(Either.forRight(codeAction));
        }
    }

    private void createCodeActionsForUnusedImport(Path path, Diagnostic diagnostic, WorkspaceFolderData folderData, List<Either<Command, CodeAction>> codeActions)
    {
        String fileText = fileTracker.getText(path);
        if(fileText == null)
        {
            return;
        }

        Range range = diagnostic.getRange();
        WorkspaceEdit edit = CodeActionsUtils.createWorkspaceEditForRemoveUnusedImport(fileText, path.toUri().toString(), diagnostic.getRange());
        if (edit == null)
        {
            return;
        }

        int startOffset = LanguageServerCompilerUtils.getOffsetFromPosition(new StringReader(fileText), range.getStart());
        int endOffset = LanguageServerCompilerUtils.getOffsetFromPosition(new StringReader(fileText), range.getEnd());

        String importText = fileText.substring(startOffset, endOffset);
        CodeAction codeAction = new CodeAction();
        codeAction.setTitle("Remove " + importText);
        codeAction.setEdit(edit);
        codeAction.setKind(CodeActionKind.QuickFix);
        codeAction.setDiagnostics(Collections.singletonList(diagnostic));
        codeActions.add(Either.forRight(codeAction));
    }
}
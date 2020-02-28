/*
 * Copyright 2018 Vizor Games LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.vizor.unreal.convert;

import com.squareup.wire.schema.internal.parser.RpcElement;
import com.squareup.wire.schema.internal.parser.ServiceElement;
import com.vizor.unreal.config.Config;
import com.vizor.unreal.provider.TypesProvider;
import com.vizor.unreal.tree.*;
import com.vizor.unreal.util.Tuple;

import java.text.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.vizor.unreal.tree.CppAnnotation.*;
import static com.vizor.unreal.tree.CppType.Kind.Class;
import static com.vizor.unreal.tree.CppType.Kind.Struct;
import static com.vizor.unreal.tree.CppType.plain;
import static com.vizor.unreal.tree.CppType.wildcardGeneric;
import static java.lang.String.join;
import static java.lang.System.lineSeparator;
import static java.text.MessageFormat.format;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;

class ClientGenerator
{
    private static final String companyName = Config.get().getCompanyName();
    // URpcDispatcher is a parent type for all dispatchers
    private static final CppType uobjectType = plain("UObject", Class);
    private static final CppType parentType = plain("URpcClient", Class);
    private static final CppType statusType = plain("grpc::Status", Class);
    private static final CppType clientContextType = plain("grpc::ClientContext", Class);
    private static final CppType tagDelegateType = plain("UTagDelegateWrapper", Class);
    private static final CppType QueueType = wildcardGeneric("TQueue", Class, 1);
    private static final CppType sharedPtrType = wildcardGeneric("TSharedPtr", Class, 1);
    private static final CppType pairType = wildcardGeneric("TPair", Class, 2);
    private static final CppType streamRecieveType = wildcardGeneric("FStreamReceiveMessage", Class, 2);
    private static final CppType streamSendType = wildcardGeneric("FStreamSendMessage", Class, 2);

    // Frequently used string literals:
    private static final String rpcRequestsCategory = companyName + "|RPC Requests|";
    private static final String rpcResponsesCategory = companyName + "|RPC Responses|";
    private static final String clientSuffix = "RpcClient";

    private final ServiceElement service;
    private final CppType boolType;
    private final CppType voidType;
    private final CppType stubType;
    // Bulk cache
    private final CppType clientType;
    private final List<RpcElement> rpcs;

    ClientGenerator(final ServiceElement service, final TypesProvider provider)
    {
        this.service = service;

        boolType = provider.getNative(boolean.class);
        voidType = provider.getNative(void.class);

        this.clientType = plain("U" + service.name() + clientSuffix, CppType.Kind.Class);
        this.stubType = plain("std::unique_ptr<lgrpc::" + service.name() + "::Stub>", CppType.Kind.Class);

        rpcs = service.rpcs();

//        requestsResponses = new HashMap<>(rpcs.size());
//        rpcs.forEach(r -> requestsResponses.put(r.name(),
//            Tuple.of(
//                provider.get(r.requestType()),
//                provider.get(r.responseType())
//            )
//        ));
    }

    CppClass genClientClass()
    {
        final List<CppField> fields = new ArrayList<>();
        final CppField stubField = new CppField(stubType, "Stub");
        stubField.enableAnnotations(false);
        fields.add(stubField);

        final List<CppFunction> methods = new ArrayList<>();
        CppFunction postInit = new CppFunction("PostInit", voidType);
        postInit.isVirtual = true;
        postInit.isOverride = true;

        final String postInitPattern = join(lineSeparator(), asList(
            "Stub = lgrpc::{0}::NewStub(Channel);"
        ));

        postInit.setBody(format(postInitPattern, service.name()));
        methods.add(postInit);

        return new CppClass(clientType, parentType, fields, methods);
    }

    List<CppClass> genConduitClass()
    {
        List<CppClass> conduits = new ArrayList<>();

        for (int i = 0 ; i < rpcs.size(); i++)
        {
            RpcElement rpc = rpcs.get(i);
            final CppType conduitType = plain("U" + service.name() + rpc.name() + "Base", Class);
            final CppType grpcRequestType = plain("lgrpc::" + rpc.requestType(), Class);
            final CppType grpcResponseType = plain("lgrpc::" + rpc.responseType(), Class);
            final List<CppField> fields = new ArrayList<>();

            fields.add(new CppField(clientType.makePtr(), "Client"));

            final List<CppFunction> methods = new ArrayList<>();

            //Blueprint callable
            if (rpc.requestStreaming() && rpc.responseStreaming()) {
                final CppType asyncReaderWriterType = plain(
                        "std::unique_ptr<grpc::ClientAsyncReaderWriter<" +
                                grpcRequestType.getName() + "," +  grpcResponseType.getName() + ">>",
                        Class);
                final CppField asyncReaderWriter = new CppField(asyncReaderWriterType, "AsyncReaderWriter");
                asyncReaderWriter.enableAnnotations(false);
                fields.add(asyncReaderWriter);
                final CppType queueElementType = pairType.makeGeneric(grpcRequestType, tagDelegateType.makePtr());
                final CppField requestQueue = new CppField(QueueType.makeGeneric(queueElementType), "RequestQueue");
                requestQueue.enableAnnotations(false);
                fields.add(requestQueue);

                final CppField responseMessage = new CppField(grpcResponseType, "TaskResponse");
                responseMessage.enableAnnotations(false);
                fields.add(responseMessage);

                final CppField streamSendTaskHandler = new CppField(sharedPtrType.makeGeneric(streamSendType.makeGeneric(conduitType, grpcRequestType)), "SendHandler");
                streamSendTaskHandler.enableAnnotations(false);
                fields.add(streamSendTaskHandler);

                final CppField streamReceiveTaskHandler = new CppField(sharedPtrType.makeGeneric(streamRecieveType.makeGeneric(conduitType, grpcResponseType)), "ReceiveHandler");
                streamReceiveTaskHandler.enableAnnotations(false);
                fields.add(streamReceiveTaskHandler);

                CppFunction startSendTaskHandler = new CppFunction("StartSendTaskHandler", voidType);
                startSendTaskHandler.enableAnnotations(false);
                startSendTaskHandler.isVirtual = true;
                startSendTaskHandler.isOverride = true;
                final String startSendTaskHandlerPattern = join(lineSeparator(), asList(
                        "SendHandler = MakeShared<FStreamSendMessage<{0}, {1}>>(this);",
                        "Client->AsyncTaskManager->AddTask(SendHandler);"
                ));
                startSendTaskHandler.setBody(format(startSendTaskHandlerPattern, conduitType, grpcRequestType));
                methods.add(startSendTaskHandler);

                CppFunction startReceiveTaskHandler = new CppFunction("StartReceiveTaskHandler", voidType);
                startReceiveTaskHandler.enableAnnotations(false);
                startReceiveTaskHandler.isVirtual = true;
                startReceiveTaskHandler.isOverride = true;
                final String startReceiveTaskHandlerPattern = join(lineSeparator(), asList(
                        "ReceiveHandler = MakeShared<FStreamReceiveMessage<{0}, {1}>>(this);",
                        "Client->AsyncTaskManager->AddTask(ReceiveHandler);"
                ));
                startReceiveTaskHandler.setBody(format(startReceiveTaskHandlerPattern, conduitType, grpcResponseType));
                methods.add(startReceiveTaskHandler);

                final List<CppArgument> queueSendMessageArgs = new ArrayList<>();
                queueSendMessageArgs.add(new CppArgument(grpcRequestType.makeConstant().makeRef(), "Request"));
                queueSendMessageArgs.add(new CppArgument(tagDelegateType.makePtr(), "DelegateWrapper"));
                CppFunction queueSendMessage = new CppFunction("QueueSendMessage", voidType, queueSendMessageArgs);
                final String queueSendMessagePattern = join(lineSeparator(), asList(
                        "if (!DelegateWrapper)",
                        "'{'",
                        "   DelegateWrapper = NewObject<UTagDelegateWrapper>(this);",
                        "   KnownTags.Add(DelegateWrapper);",
                        "'}'",
                        "if (DelegateWrapper->bBindDefault)",
                        "'{'",
                        "   DelegateWrapper->Delegate.AddUObject(this, &{0}::OnMessageSent, Request);",
                        "'}'",
                        "RequestQueue.Enqueue(TPair<{1}, UTagDelegateWrapper*>(Request, DelegateWrapper));"
                ));
                queueSendMessage.enableAnnotations(false);
                queueSendMessage.isVirtual = true;
                queueSendMessage.setBody(format(queueSendMessagePattern, conduitType, grpcRequestType ));
                methods.add(queueSendMessage);

                final List<CppArgument> sendMessageArgs = new ArrayList<>();
                sendMessageArgs.add(new CppArgument(grpcRequestType.makeConstant().makeRef(), "Request"));
                sendMessageArgs.add(new CppArgument(tagDelegateType.makePtr(), "DelegateWrapper"));
                CppFunction sendMessage = new CppFunction("SendMessage", voidType, sendMessageArgs);
                final String sendMessagePattern = join(lineSeparator(), asList(
                        "if (bSendingMessage)",
                        "'{'",
                        "   UE_LOG(LogTemp, Fatal, TEXT(\"Double message send when streaming\"));",
                        "'}'",
                        "bSendingMessage = true;",
                        "if (!DelegateWrapper)",
                        "'{'",
                        "   DelegateWrapper = NewObject<UTagDelegateWrapper>(this);",
                        "   KnownTags.Add(DelegateWrapper);",
                        "'}'",
                        "if (DelegateWrapper->bBindDefault)",
                        "'{'",
                        "   DelegateWrapper->Delegate.AddUObject(this, &{0}::OnMessageSent, Request);",
                        "'}'",
                        "AsyncReaderWriter->Write(Request, DelegateWrapper);"
                ));
                sendMessage.enableAnnotations(false);
                sendMessage.isVirtual = true;
                sendMessage.setBody(format(sendMessagePattern, conduitType, grpcRequestType ));
                methods.add(sendMessage);

                final List<CppArgument> receiveMessageArgs = new ArrayList<>();
                receiveMessageArgs.add(new CppArgument(grpcResponseType.makePtr(), "Response"));
                receiveMessageArgs.add(new CppArgument(tagDelegateType.makePtr(), "DelegateWrapper"));
                CppFunction receiveMessage = new CppFunction("ReceiveMessage", voidType, receiveMessageArgs);
                final String receiveMessagePattern = join(lineSeparator(), asList(
                        "if (bReceivingMessage)",
                        "'{'",
                        "   UE_LOG(LogTemp, Fatal, TEXT(\"Double message receive when streaming\"));",
                        "'}'",
                        "bReceivingMessage = true;",
                        "if (!DelegateWrapper)",
                        "'{'",
                        "   DelegateWrapper = NewObject<UTagDelegateWrapper>(this);",
                        "   KnownTags.Add(DelegateWrapper);",
                        "'}'",
                        "if (DelegateWrapper->bBindDefault)",
                        "'{'",
                        "   DelegateWrapper->Delegate.AddUObject(this, &{0}::OnMessageReceived, Response);",
                        "'}'",
                        "AsyncReaderWriter->Read(Response, DelegateWrapper);"
                ));
                receiveMessage.enableAnnotations(false);
                receiveMessage.isVirtual = true;
                receiveMessage.setBody(format(receiveMessagePattern, conduitType));
                methods.add(receiveMessage);

                final List<CppArgument> startStreamArgs = new ArrayList<>();
                startStreamArgs.add(new CppArgument(tagDelegateType.makePtr(), "DelegateWrapper"));
                CppFunction startStream = new CppFunction("StartStream", voidType, startStreamArgs);
                final String startStreamPattern = join(lineSeparator(), asList(
                        "if (!DelegateWrapper)",
                        "'{'",
                        "   DelegateWrapper = NewObject<UTagDelegateWrapper>(this);",
                        "   KnownTags.Add(DelegateWrapper);",
                        "'}'",
                        "if (DelegateWrapper->bBindDefault)",
                        "'{'",
                        "   DelegateWrapper->Delegate.AddUObject(this, &{1}::OnStreamStarted);",
                        "'}'",
                        "AsyncReaderWriter = Client->Stub->Async{0}(&ClientContext, &Client->CompletionQueue, DelegateWrapper);"
                ));
                startStream.enableAnnotations(false);
                startStream.isVirtual = true;
                startStream.isOverride = true;
                startStream.setBody(format(startStreamPattern, rpc.name(), conduitType));
                methods.add(startStream);

                final List<CppArgument> endStreamArgs = new ArrayList<>();
                endStreamArgs.add(new CppArgument(tagDelegateType.makePtr(), "DelegateWrapper"));
                CppFunction endStream = new CppFunction("EndStream", voidType, endStreamArgs);
                final String endStreamPattern = join(lineSeparator(), asList(
                        "if (!DelegateWrapper)",
                        "'{'",
                        "   DelegateWrapper = NewObject<UTagDelegateWrapper>(this);",
                        "   KnownTags.Add(DelegateWrapper);",
                        "'}'",
                        "if (DelegateWrapper->bBindDefault)",
                        "'{'",
                        "   DelegateWrapper->Delegate.AddUObject(this, &{0}::OnStreamFinished);",
                        "'}'",
                        "AsyncReaderWriter->Finish(&Status, DelegateWrapper);"
                ));
                endStream.enableAnnotations(false);
                endStream.isVirtual = true;
                endStream.isOverride = true;
                endStream.setBody(format(endStreamPattern, conduitType));
                methods.add(endStream);

                final List<CppArgument> messageSentArgs = new ArrayList<>();
                messageSentArgs.add(new CppArgument(boolType, "Ok"));
                messageSentArgs.add(new CppArgument(grpcRequestType, "Request"));
                CppFunction messageSent = new CppFunction("OnMessageSent", voidType, messageSentArgs);
                messageSent.enableAnnotations(false);
                messageSent.isVirtual = true;
                final String messageSentPattern = join(lineSeparator(), asList(
                        "bSendingMessage = false;"
                ));
                messageSent.setBody(messageSentPattern);
                methods.add(messageSent);

                final List<CppArgument> onMessageReceivedArgs = new ArrayList<>();
                onMessageReceivedArgs.add(new CppArgument(boolType, "Ok"));
                onMessageReceivedArgs.add(new CppArgument(grpcResponseType.makeConstant().makePtr(), "Response"));
                CppFunction messageReceived = new CppFunction("OnMessageReceived", voidType, onMessageReceivedArgs);
                messageReceived.enableAnnotations(false);
                messageReceived.isVirtual = true;
                final String messageReceivedPattern = join(lineSeparator(), asList(
                        "bReceivingMessage = false;"
                ));
                messageReceived.setBody(messageReceivedPattern);
                methods.add(messageReceived);

                final List<CppArgument> onStreamStartedArgs = new ArrayList<>();
                onStreamStartedArgs.add(new CppArgument(boolType, "Ok"));
                CppFunction onStreamStarted = new CppFunction("OnStreamStarted", voidType, onStreamStartedArgs);
                onStreamStarted.enableAnnotations(false);
                onStreamStarted.isVirtual = true;
                onStreamStarted.isOverride = true;
                final String onStreamStartedPattern = join(lineSeparator(), asList(
                        "Super::OnStreamStarted(Ok);",
                        "TaskReceiveDelegate->Delegate.AddUObject(this, &ULaytonClientLobbyStreamBase::OnMessageReceived, &TaskResponse);"
                ));
                onStreamStarted.setBody(format(onStreamStartedPattern, conduitType, grpcRequestType));
                methods.add(onStreamStarted);

                conduits.add(new CppClass(conduitType, plain("UBidirectionalStreamConduitBase", Class), fields, methods));
            } else {
                final CppField requestField = new CppField(grpcRequestType, "Request");
                final CppField responseField = new CppField(grpcResponseType, "Response");
                requestField.enableAnnotations(false);
                responseField.enableAnnotations(false);
                fields.add(requestField);
                fields.add(responseField);

                final CppType responseReaderType = plain(
                        "std::unique_ptr<grpc::ClientAsyncResponseReader<" + grpcResponseType.getName() + ">>",
                        Class);
                final CppField responseReader = new CppField(responseReaderType, "ResponseReader");
                responseReader.enableAnnotations(false);
                fields.add(responseReader);

                //Activate  (Request)
                CppFunction activate = new CppFunction("Activate", voidType);
                activate.enableAnnotations(false);
                activate.isVirtual = true;
                activate.isOverride = true;

//                final String activatePattern = join(lineSeparator(), asList(
//                        "ResponseReader = Client->Stub->Async{0}(&ClientContext, Request, &Client->CompletionQueue);",
//                        "OnTagRecieved.AddUObject(this, &UUnaryConduitBase::TagRecieved);",
//                        "ResponseReader->Finish(&Response, &Status, &OnTagRecieved);"
//                ));

                final String activatePattern = join(lineSeparator(), asList(
                        "Tag = NewObject<UTagDelegateWrapper>(this);",
                        "Tag->Delegate.AddUObject(this, &UUnaryConduitBase::OnResponseReceived);",
                        "ResponseReader = Client->Stub->Async{0}(&ClientContext, Request, &Client->CompletionQueue);",
                        "ResponseReader->Finish(&Response, &Status, Tag);"
                ));

                activate.setBody(format(activatePattern, rpc.name()));
                methods.add(activate);

                CppClass conduit = new CppClass(conduitType, plain("UUnaryConduitBase", Class), fields, methods);
                conduit.addAnnotation(Abstract);
                conduits.add(conduit);
            }
        }

        return conduits;
    }

    List<CppDelegate> genDelegates()
    {
        List<CppDelegate> delegates = new ArrayList<>();

//        for (int i = 0 ; i < rpcs.size(); i++)
//        {
//            RpcElement rpc = rpcs.get(i);
//
//            rpc.responseStreaming();
//            final CppType delegateType;
//            if (rpc.requestStreaming() && rpc.responseStreaming()) {
//                delegateType = plain("F" + service.name() + rpc.name() + "OnResponseDelegate", Class);
//            }
//            else {
//                delegateType = plain("F" + service.name() + rpc.name() + "OnCompleteDelegate", Class);
//            }
//
//            final CppType ue4ResponseType = plain("FLayton" + rpc.responseType(), Class);
//            final List<CppArgument> args = new ArrayList<>();
//            args.add(new CppArgument(ue4ResponseType.makeConstant().makeRef(), "OutResponse"));
//            args.add(new CppArgument(statusType.makePtr(), "OutStatus"));
//            delegates.add(new CppDelegate(delegateType, args));
//        }
        return delegates;
    }
}

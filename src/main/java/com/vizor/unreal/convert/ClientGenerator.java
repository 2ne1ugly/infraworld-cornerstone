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
    private static final CppType statusType = plain("FGrpcStatus", Class);

    // Frequently used string literals:
    private static final String rpcRequestsCategory = companyName + "|RPC Requests|";
    private static final String rpcResponsesCategory = companyName + "|RPC Responses|";
    private static final String clientSuffix = "RpcClient";

    // Special structures, wrapping requests and responses:
    static final CppType reqWithCtx = wildcardGeneric("TRequestWithContext", Struct, 1);
    static final CppType rspWithSts = wildcardGeneric("TResponseWithStatus", Struct, 1);
    static final CppArgument contextArg = new CppArgument(plain("FGrpcClientContext", Struct).makeRef(), "Context");

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
        final List<CppFunction> methods = new ArrayList<>();

        final List<CppField> fields = new ArrayList<>();
        final CppField stubField = new CppField(stubType, "Stub");
        stubField.enableAnnotations(false);
        fields.add(stubField);

        return new CppClass(clientType, parentType, fields, methods);
    }

    List<CppClass> genConduitClass()
    {
        List<CppClass> conduits = new ArrayList<>();

        for (int i = 0 ; i < rpcs.size(); i++)
        {
            RpcElement rpc = rpcs.get(i);
            final CppType conduitType = plain("U" + service.name() + rpc.name(), Class);
            final CppType ue4RequestType = plain("F" + service.name() + "_" + rpc.requestType(), Class);
            final CppType ue4ResponseType = plain("F" + service.name() + "_" + rpc.responseType(), Class);
            final CppType grpcRequestType = plain("lgrpc::" + rpc.requestType(), Class);
            final CppType grpcResponseType = plain("lgrpc::" + rpc.responseType(), Class);
            final List<CppField> fields = new ArrayList<>();

            final CppType delegateType = plain("F" + service.name() + rpc.name() + "OnCompleteDelegate", Class);
            final CppField delegateField = new CppField(delegateType, "OnComplete");
            delegateField.addAnnotation(BlueprintAssignable);
            fields.add(delegateField);

            fields.add(new CppField(clientType.makePtr(), "Client"));

            final CppField requestField = new CppField(grpcRequestType, "Request");
            final CppField responseField = new CppField(grpcResponseType, "Response");
            requestField.enableAnnotations(false);
            responseField.enableAnnotations(false);
            fields.add(requestField);
            fields.add(responseField);

            final List<CppFunction> methods = new ArrayList<>();

            //Blueprint callable
            final List<CppArgument> args = new ArrayList<>();
            args.add(new CppArgument(uobjectType.makePtr(), "WorldContextObject"));
            args.add(new CppArgument(clientType.makePtr(), "InClient"));
            args.add(new CppArgument(ue4RequestType.makeConstant().makeRef(), "InRequest"));
            CppFunction rpcStatic = new CppFunction(service.name() + rpc.name(), conduitType.makePtr(), args);
            rpcStatic.addAnnotation(BlueprintCallable);
            rpcStatic.addAnnotation(Category, rpcRequestsCategory + service.name());
            rpcStatic.addAnnotation(WorldContext, "WorldContextObject");
            rpcStatic.isStatic = true;

            final String rpcStaticPattern = join(lineSeparator(), asList(
                    "{0}* Conduit = NewObject<{0}>(WorldContextObject);",
                    "Conduit->Client = InClient;",
                    "Conduit->Request = casts::Proto_Cast<{1}>(InRequest);",
                    "return Conduit;"
            ));
            rpcStatic.setBody(format(rpcStaticPattern, conduitType, grpcRequestType));

            methods.add(rpcStatic);

            //Activate  (Request)
            CppFunction activate = new CppFunction("Activate", voidType);
            activate.enableAnnotations(false);
            activate.isVirtual = true;
            activate.isOverride = true;

            final String activatePattern = join(lineSeparator(), asList(
                    "Client->Stub->Async{0}(&ClientContext, Request, &Client->CompletionQueue)->Finish(&Response, &Status, this);"
            ));
            activate.setBody(format(activatePattern, rpc.name()));
            methods.add(activate);

            //process  (Response)
            CppFunction process = new CppFunction("Process", voidType);
            process.enableAnnotations(false);
            process.isVirtual = true;
            process.isOverride = true;

            final String processPattern = join(lineSeparator(), asList(
                    "OnComplete.Broadcast(casts::Proto_Cast<FGrpcStatus>(Status), casts::Proto_Cast<{0}>(Response));"
            ));
            process.setBody(format(processPattern, ue4ResponseType));
            methods.add(process);

            conduits.add(new CppClass(plain("U" + service.name() + rpc.name(), Class), plain("UAsyncConduitBase", Class), fields, methods));
        }

        return conduits;
    }

    List<CppDelegate> genDelegates()
    {
        List<CppDelegate> delegates = new ArrayList<>();

        for (int i = 0 ; i < rpcs.size(); i++)
        {
            RpcElement rpc = rpcs.get(i);

            final CppType delegateType = plain("F" + service.name() + rpc.name() + "OnCompleteDelegate", Class);
            final CppType ue4ResponseType = plain("F" + service.name() + "_" + rpc.responseType(), Class);
            final List<CppArgument> args = new ArrayList<>();
            args.add(new CppArgument(statusType.makeConstant().makeRef(), "OutStatus"));
            args.add(new CppArgument(ue4ResponseType.makeConstant().makeRef(), "OutResponse"));
            delegates.add(new CppDelegate(delegateType, args));
        }
        return delegates;
    }

    static String supressSuperString(final String functionName)
    {
        return "// No need to call Super::" + functionName + "(), it isn't required by design" + lineSeparator();
    }
}

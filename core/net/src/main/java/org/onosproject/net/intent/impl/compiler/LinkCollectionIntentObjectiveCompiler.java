/*
 * Copyright 2016-present Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.net.intent.impl.compiler;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.onlab.util.Identifier;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.domain.DomainService;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.flow.criteria.EthCriterion;
import org.onosproject.net.flow.instructions.Instruction;
import org.onosproject.net.flowobjective.DefaultFilteringObjective;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.DefaultNextObjective;
import org.onosproject.net.flowobjective.FilteringObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.flowobjective.NextObjective;
import org.onosproject.net.flowobjective.Objective;
import org.onosproject.net.intent.FlowObjectiveIntent;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.IntentCompiler;
import org.onosproject.net.intent.LinkCollectionIntent;
import org.onosproject.net.intent.constraint.EncapsulationConstraint;
import org.onosproject.net.resource.ResourceService;
import org.onosproject.net.resource.impl.LabelAllocator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.onosproject.net.domain.DomainId.LOCAL;
import static org.onosproject.net.flow.instructions.Instruction.Type.OUTPUT;

/**
 * Compiler to produce flow objectives from link collections.
 */
@Component(immediate = true)
public class LinkCollectionIntentObjectiveCompiler
        extends LinkCollectionCompiler<Objective>
        implements IntentCompiler<LinkCollectionIntent> {

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected IntentConfigurableRegistrator registrator;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowObjectiveService flowObjectiveService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ResourceService resourceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DomainService domainService;

    private ApplicationId appId;

    @Activate
    public void activate() {
        appId = coreService.registerApplication("org.onosproject.net.intent");
        registrator.registerCompiler(LinkCollectionIntent.class, this, true);
        if (labelAllocator == null) {
            labelAllocator = new LabelAllocator(resourceService);
        }
    }


    @Deactivate
    public void deactivate() {
        registrator.unregisterCompiler(LinkCollectionIntent.class, true);
    }

    @Override
    public List<Intent> compile(LinkCollectionIntent intent, List<Intent> installable) {

        SetMultimap<DeviceId, PortNumber> inputPorts = HashMultimap.create();
        SetMultimap<DeviceId, PortNumber> outputPorts = HashMultimap.create();
        Map<ConnectPoint, Identifier<?>> labels = ImmutableMap.of();

        Optional<EncapsulationConstraint> encapConstraint = this.getIntentEncapConstraint(intent);

        computePorts(intent, inputPorts, outputPorts);

        if (encapConstraint.isPresent()) {
            labels = labelAllocator.assignLabelToPorts(intent.links(),
                                                       intent.key(),
                                                       encapConstraint.get().encapType());
        }

        ImmutableList.Builder<Intent> intentList = ImmutableList.builder();
        if (this.isDomainProcessingEnabled(intent)) {
            intentList.addAll(this.getDomainIntents(intent, domainService));
        }

        List<Objective> objectives = new ArrayList<>();
        List<DeviceId> devices = new ArrayList<>();
        for (DeviceId deviceId : outputPorts.keySet()) {
            // add only objectives that are not inside of a domain
            if (LOCAL.equals(domainService.getDomain(deviceId))) {
                List<Objective> deviceObjectives =
                        createRules(intent,
                                    deviceId,
                                    inputPorts.get(deviceId),
                                    outputPorts.get(deviceId),
                                    labels);
                deviceObjectives.forEach(objective -> {
                    objectives.add(objective);
                    devices.add(deviceId);
                });
            }
        }
        // if any objectives have been created
        if (!objectives.isEmpty()) {
            intentList.add(new FlowObjectiveIntent(appId, intent.key(), devices,
                                                   objectives,
                                                   intent.resources(),
                                                   intent.resourceGroup()));
        }
        return intentList.build();
    }

    @Override
    boolean optimizeTreatments() {
        return false;
    }

    @Override
    protected List<Objective> createRules(LinkCollectionIntent intent,
                                          DeviceId deviceId,
                                          Set<PortNumber> inPorts,
                                          Set<PortNumber> outPorts,
                                          Map<ConnectPoint, Identifier<?>> labels) {

        List<Objective> objectives = new ArrayList<>(inPorts.size() * 2);

        /*
         * Looking for the encapsulation constraint
         */
        Optional<EncapsulationConstraint> encapConstraint = this.getIntentEncapConstraint(intent);

        inPorts.forEach(inport -> {

            ForwardingInstructions instructions = this.createForwardingInstruction(
                    encapConstraint,
                    intent,
                    inport,
                    outPorts,
                    deviceId,
                    labels
            );

            Set<TrafficTreatment> treatmentsWithDifferentPort =
                    Sets.newHashSet();

            TrafficTreatment.Builder treatmentBuilder =
                    DefaultTrafficTreatment.builder();

            for (Instruction inst : instructions.treatment().allInstructions()) {
                if (inst.type() == OUTPUT) {
                    treatmentBuilder.add(inst);
                    treatmentsWithDifferentPort.add(treatmentBuilder.build());
                    treatmentBuilder = DefaultTrafficTreatment.builder();
                } else {
                    treatmentBuilder.add(inst);
                }
            }

            EthCriterion ethDst = (EthCriterion) instructions.selector()
                    .getCriterion(Criterion.Type.ETH_DST);
            boolean broadcastObjective = ethDst != null &&
                    (ethDst.mac().isBroadcast() || ethDst.mac().isMulticast());

            if (treatmentsWithDifferentPort.size() < 2 && !broadcastObjective) {
                objectives.addAll(createSimpleNextObjective(instructions, intent));
            } else {
                objectives.addAll(createBroadcastObjective(instructions,
                                                           treatmentsWithDifferentPort,
                                                           intent));
            }
        });

        return objectives;
    }

    private List<Objective> createBroadcastObjective(ForwardingInstructions instructions,
                                                     Set<TrafficTreatment> treatmentsWithDifferentPort,
                                                     LinkCollectionIntent intent) {
        List<Objective> objectives = Lists.newArrayList();
        FilteringObjective filteringObjective;
        ForwardingObjective forwardingObjective;
        NextObjective nextObjective;

        Integer nextId = flowObjectiveService.allocateNextId();

        forwardingObjective = buildForwardingObjective(instructions.selector(),
                                                       nextId, intent.priority());

        DefaultNextObjective.Builder nxBuilder = DefaultNextObjective.builder();
        nxBuilder.withId(nextId)
                .withMeta(instructions.selector())
                .withType(NextObjective.Type.BROADCAST)
                .fromApp(appId)
                .withPriority(intent.priority())
                .makePermanent();

        treatmentsWithDifferentPort.forEach(nxBuilder::addTreatment);
        nextObjective = nxBuilder.add();

        filteringObjective = buildFilteringObjective(instructions.selector(), intent.priority());

        objectives.add(filteringObjective);
        objectives.add(forwardingObjective);
        objectives.add(nextObjective);

        return objectives;
    }

    private List<Objective> createSimpleNextObjective(ForwardingInstructions instructions,
                                                      LinkCollectionIntent intent) {
        List<Objective> objectives = Lists.newArrayList();
        FilteringObjective filteringObjective;
        ForwardingObjective forwardingObjective;
        NextObjective nextObjective;

        Integer nextId = flowObjectiveService.allocateNextId();

        forwardingObjective = buildForwardingObjective(instructions.selector(),
                                                       nextId, intent.priority());

        DefaultNextObjective.Builder nxBuilder = DefaultNextObjective.builder();
        nextObjective = nxBuilder.withId(nextId)
                .withMeta(instructions.selector())
                .addTreatment(instructions.treatment())
                .withType(NextObjective.Type.SIMPLE)
                .fromApp(appId)
                .makePermanent()
                .withPriority(intent.priority())
                .add();

        filteringObjective = buildFilteringObjective(instructions.selector(), intent.priority());

        objectives.add(filteringObjective);
        objectives.add(forwardingObjective);
        objectives.add(nextObjective);

        return objectives;
    }

    private ForwardingObjective buildForwardingObjective(TrafficSelector selector, Integer nextId, int priority) {
        return DefaultForwardingObjective.builder()
                .withMeta(selector)
                .withSelector(selector)
                .nextStep(nextId)
                .fromApp(appId)
                .withPriority(priority)
                .withFlag(ForwardingObjective.Flag.SPECIFIC)
                .makePermanent()
                .add();
    }

    private FilteringObjective buildFilteringObjective(TrafficSelector selector, int priority) {
        FilteringObjective.Builder builder = DefaultFilteringObjective.builder();
        builder.fromApp(appId)
                .permit()
                .makePermanent()
                .withPriority(priority);

        Criterion inPortCriterion =
                selector.getCriterion(Criterion.Type.IN_PORT);

        if (inPortCriterion != null) {
            builder.withKey(inPortCriterion);
        }

        selector.criteria().forEach(builder::addCondition);
        return builder.add();
    }

}

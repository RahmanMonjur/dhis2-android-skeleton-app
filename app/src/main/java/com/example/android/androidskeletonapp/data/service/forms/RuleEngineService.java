package com.example.android.androidskeletonapp.data.service.forms;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.collect.Lists;

import org.apache.commons.jexl2.JexlEngine;
import org.hisp.dhis.android.core.D2;
import org.hisp.dhis.android.core.arch.helpers.UidsHelper;
import org.hisp.dhis.android.core.common.ValueType;
import org.hisp.dhis.android.core.dataelement.DataElement;
import org.hisp.dhis.android.core.enrollment.Enrollment;
import org.hisp.dhis.android.core.event.Event;
import org.hisp.dhis.android.core.event.EventStatus;
import org.hisp.dhis.android.core.organisationunit.OrganisationUnit;
import org.hisp.dhis.android.core.organisationunit.OrganisationUnitGroup;
import org.hisp.dhis.android.core.program.Program;
import org.hisp.dhis.android.core.program.ProgramRule;
import org.hisp.dhis.android.core.program.ProgramRuleAction;
import org.hisp.dhis.android.core.program.ProgramRuleVariable;
import org.hisp.dhis.android.core.program.ProgramTrackedEntityAttribute;
import org.hisp.dhis.android.core.trackedentity.TrackedEntityAttribute;
import org.hisp.dhis.android.core.trackedentity.TrackedEntityAttributeValue;
import org.hisp.dhis.android.core.trackedentity.TrackedEntityDataValue;
import org.hisp.dhis.rules.RuleEngine;
import org.hisp.dhis.rules.RuleEngineContext;
import org.hisp.dhis.rules.RuleExpressionEvaluator;
import org.hisp.dhis.rules.models.Rule;
import org.hisp.dhis.rules.models.RuleAction;
import org.hisp.dhis.rules.models.RuleActionAssign;
import org.hisp.dhis.rules.models.RuleActionCreateEvent;
import org.hisp.dhis.rules.models.RuleActionDisplayKeyValuePair;
import org.hisp.dhis.rules.models.RuleActionDisplayText;
import org.hisp.dhis.rules.models.RuleActionErrorOnCompletion;
import org.hisp.dhis.rules.models.RuleActionHideField;
import org.hisp.dhis.rules.models.RuleActionHideSection;
import org.hisp.dhis.rules.models.RuleActionSetMandatoryField;
import org.hisp.dhis.rules.models.RuleActionShowError;
import org.hisp.dhis.rules.models.RuleActionShowWarning;
import org.hisp.dhis.rules.models.RuleActionWarningOnCompletion;
import org.hisp.dhis.rules.models.RuleAttributeValue;
import org.hisp.dhis.rules.models.RuleDataValue;
import org.hisp.dhis.rules.models.RuleEnrollment;
import org.hisp.dhis.rules.models.RuleEvent;
import org.hisp.dhis.rules.models.RuleValueType;
import org.hisp.dhis.rules.models.RuleVariable;
import org.hisp.dhis.rules.models.RuleVariableAttribute;
import org.hisp.dhis.rules.models.RuleVariableCalculatedValue;
import org.hisp.dhis.rules.models.RuleVariableCurrentEvent;
import org.hisp.dhis.rules.models.RuleVariableNewestEvent;
import org.hisp.dhis.rules.models.RuleVariableNewestStageEvent;
import org.hisp.dhis.rules.models.RuleVariablePreviousEvent;
import org.hisp.dhis.rules.models.TriggerEnvironment;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import io.reactivex.Flowable;

import static android.text.TextUtils.isEmpty;

public class RuleEngineService {

    private D2 d2;
    private String stage;
    private JexlEngine jexlEngine;

    private enum evaluationType {
        ENROLLMENT, EVENT
    }

    private String programUid;
    private String eventUid;
    private String enrollmentUid;
    private String orgUnit;

    public Flowable<RuleEngine> configure(D2 d2, String programUid, @Nullable String enrollmentUid,
                                          @Nullable String eventUid) {
        this.d2 = d2;
        this.programUid = programUid;
        this.enrollmentUid = enrollmentUid;
        this.eventUid = eventUid;
        this.stage = null;
        this.orgUnit = this.eventUid != null ? d2.eventModule().events().uid(eventUid).blockingGet().organisationUnit() : "";

        jexlEngine = new JexlEngine();

        return Flowable.zip(
                getRuleVariables(),
                getRules(),
                getEvents(enrollmentUid),
                getSupplementaryData(orgUnit),
                (ruleVariables, rules, events, supplementaryData) -> RuleEngineContext.builder(new RuleExpressionEvaluator() {
                    @Nonnull
                    @Override
                    public String evaluate(@Nonnull String expression) {
                        return jexlEngine.createExpression(expression).evaluate(null).toString();
                    }
                })
                        .ruleVariables(ruleVariables)
                        .rules(rules)
                        .supplementaryData(supplementaryData)
                        .calculatedValueMap(new HashMap<>())
                        .constantsValue(queryConstants())
                        .build().toEngineBuilder()
                        .triggerEnvironment(TriggerEnvironment.ANDROIDCLIENT)
                        .events(events)
                        .build()
        );
    }

    public Flowable<RuleEngine> configure(D2 d2, String programUid, String eventUid) {
        this.d2 = d2;
        this.programUid = programUid;
        this.enrollmentUid = d2.eventModule().events().uid(eventUid).blockingGet().enrollment();
        this.eventUid = eventUid;
        this.stage = null;
        this.orgUnit = !this.eventUid.isEmpty() ? d2.eventModule().events().uid(eventUid).blockingGet().organisationUnit() : "";

        jexlEngine = new JexlEngine();

        Flowable<RuleEngine> initialFlowable;

        if (isEmpty(enrollmentUid))
            initialFlowable = Flowable.zip(
                    getRuleVariables(),
                    getRules(),
                    getOtherEvents(eventUid),
                    getSupplementaryData(orgUnit),
                    (ruleVariables, rules, events, supplementaryData) -> setUp(ruleVariables, rules, events, null, supplementaryData));
        else
            initialFlowable = Flowable.zip(
                    getRuleVariables(),
                    getRules(),
                    getEvents(enrollmentUid, eventUid),
                    //getOtherEvents(eventUid),
                    ruleEnrollment(),
                    getSupplementaryData(orgUnit),
                    this::setUp);

        return initialFlowable;
    }

    public RuleEngine setUp(List<RuleVariable> ruleVariables,
                            List<Rule> rules,
                            List<RuleEvent> events,
                            RuleEnrollment enrollment,
                            Map<String, List<String>> suplementaryData) {
        RuleEngine.Builder builder = RuleEngineContext.builder(new RuleExpressionEvaluator() {
            @Nonnull
            @Override
            public String evaluate(@Nonnull String expression) {
                return jexlEngine.createExpression(expression).evaluate(null).toString();
            }
        })
                .ruleVariables(ruleVariables)
                .rules(rules)
                .supplementaryData(suplementaryData)
                .calculatedValueMap(new HashMap<>())
                .constantsValue(queryConstants())
                .build().toEngineBuilder()
                .triggerEnvironment(TriggerEnvironment.ANDROIDCLIENT)
                .events(events);
        if (enrollment != null)
            builder.enrollment(enrollment);
        return builder.build();
    }

    public Flowable<RuleEnrollment> ruleEnrollment() {
        return Flowable.fromCallable(() -> {
            Enrollment enrollment = d2.enrollmentModule().enrollments().uid(enrollmentUid).blockingGet();
            String ouCode = d2.organisationUnitModule().organisationUnits().uid(enrollment.organisationUnit())
                    .blockingGet().code();
            Program program = d2.programModule().programs().uid(enrollment.program()).blockingGet();
            List<ProgramTrackedEntityAttribute> programTrackedEntityAttributes =
                    d2.programModule().programTrackedEntityAttributes()
                            .byProgram().eq(enrollment.program()).blockingGet();
            List<String> programAttributesUids = getProgramTrackedEntityAttributesUids(programTrackedEntityAttributes);

            List<RuleAttributeValue> attributeValues = transformToRuleAttributeValues(
                    d2.trackedEntityModule().trackedEntityAttributeValues()
                            .byTrackedEntityInstance().eq(enrollment.trackedEntityInstance())
                            .byTrackedEntityAttribute().in(programAttributesUids)
                            .blockingGet()
            );
            return RuleEnrollment.create(
                    enrollment.uid(),
                    enrollment.incidentDate(),
                    enrollment.enrollmentDate(),
                    enrollment.status() != null ?
                            RuleEnrollment.Status.valueOf(enrollment.status().name()) :
                            RuleEnrollment.Status.ACTIVE,
                    enrollment.organisationUnit(),
                    ouCode,
                    attributeValues,
                    program.name()
            );
        });
    }

    public Flowable<RuleEvent> ruleEvent() {
        if (eventUid == null)
            return Flowable.empty();
        else
            return Flowable.fromCallable(() ->
                    transformToRuleEvent(d2.eventModule().events().uid(eventUid).blockingGet()));
    }

    private List<String> getProgramTrackedEntityAttributesUids(
            List<ProgramTrackedEntityAttribute> programTrackedEntityAttributes) {
        List<String> attrUids = new ArrayList<>();
        for (ProgramTrackedEntityAttribute programTrackedEntityAttribute : programTrackedEntityAttributes)
            attrUids.add(programTrackedEntityAttribute.uid());
        return attrUids;
    }

    private List<RuleAttributeValue> transformToRuleAttributeValues(List<TrackedEntityAttributeValue> attributeValues) {
        List<RuleAttributeValue> ruleAttributeValues = new ArrayList<>();

        for (TrackedEntityAttributeValue attributeValue : attributeValues)
            ruleAttributeValues.add(
                    RuleAttributeValue.create(attributeValue.trackedEntityAttribute(), attributeValue.value())
            );

        return ruleAttributeValues;
    }

    private Flowable<List<RuleEvent>> getEvents(String enrollmentUid) {
        return Flowable.fromCallable(() -> d2.eventModule().events()
                .byEnrollmentUid().eq(enrollmentUid)
                .byStatus().in(EventStatus.ACTIVE, EventStatus.COMPLETED)
                .blockingGet()).flatMapIterable(events -> events)
                .map(this::transformToRuleEvent)
                .toList().toFlowable();
    }

    private Flowable<List<RuleEvent>> getEvents(String enrollmentUid, String eventUid) {
        return Flowable.fromCallable(() -> d2.eventModule().events().uid(eventUid).blockingGet())
                .flatMap(event ->
                        Flowable.fromCallable(() -> d2.eventModule().events()
                                .byProgramUid().eq(event.program())
                                .byEnrollmentUid().eq(enrollmentUid)
                                .byUid().notIn(event.uid())
                                .byStatus().in(EventStatus.ACTIVE, EventStatus.COMPLETED)
                                .blockingGet()))
                .flatMapIterable(events -> events)
                .map(this::transformToRuleEvent)
                .toList().toFlowable();
    }

    private Flowable<List<RuleEvent>> getOtherEvents(String eventUid) {
        return Flowable.fromCallable(() -> d2.eventModule().events().uid(eventUid).blockingGet())
                .flatMap(event ->
                        Flowable.fromCallable(() -> d2.eventModule().events()
                                .byProgramUid().eq(event.program())
                                .byUid().notIn(event.uid())
                                .byEventDate().before(event.eventDate())
                                .byStatus().in(EventStatus.ACTIVE, EventStatus.COMPLETED)
                                .blockingGet()))
                .flatMapIterable(events -> events)
                .map(this::transformToRuleEvent)
                .toList().toFlowable();
    }

    private RuleEvent transformToRuleEvent(Event event) {
        String code = d2.organisationUnitModule().organisationUnits()
                .uid(event.organisationUnit()).blockingGet().code();
        String stageName = d2.programModule().programStages().uid(event.programStage()).blockingGet().name();
        List<TrackedEntityDataValue> eventDataValue = d2.trackedEntityModule().trackedEntityDataValues()
                .byEvent().eq(event.uid()).blockingGet();
        List<RuleDataValue> ruleDataValues = transformToRuleDataValue(event, eventDataValue);
        return RuleEvent.create(event.uid(),
                event.programStage(),
                RuleEvent.Status.valueOf(event.status().name()),
                event.eventDate(),
                event.dueDate() != null ? event.dueDate() : event.eventDate(),
                event.organisationUnit(),
                code,
                ruleDataValues,
                stageName);
    }

    private List<RuleDataValue> transformToRuleDataValue(Event event, List<TrackedEntityDataValue> eventDataValues) {
        List<RuleDataValue> ruleDataValues = new ArrayList<>();
        for (TrackedEntityDataValue dataValue : eventDataValues) {
            ruleDataValues.add(RuleDataValue.create(
                    event.eventDate(), event.programStage(), dataValue.dataElement(), dataValue.value())
            );
        }
        return ruleDataValues;
    }

    private Flowable<List<Rule>> getRules() {
        return Flowable.fromCallable(() -> d2.programModule().programRules()
                .byProgramUid().eq(programUid).withProgramRuleActions().blockingGet())
                .map(this::transformToRule);
    }

    private Flowable<List<RuleVariable>> getRuleVariables() {
        return Flowable.fromCallable(() -> d2.programModule().programRuleVariables()
                .byProgramUid().eq(programUid).blockingGet())
                .map(this::transformToRuleVariable);
    }

    private List<Rule> transformToRule(List<ProgramRule> programRules) {
        List<Rule> rules = new ArrayList<>();

        for (ProgramRule rule : programRules) {

            try {
                List<RuleAction> ruleActions = transformToRuleAction(rule.programRuleActions());
                rules.add(
                        Rule.create(rule.programStage() != null ?
                                rule.programStage().uid() :
                                null, rule.priority(), rule.condition(), ruleActions, rule.name())
                );
            } catch (Exception e){
                System.out.println("Error in ProgramRule: " + rule.uid());
            }
        }

        return rules;
    }

    private List<RuleAction> transformToRuleAction(List<ProgramRuleAction> programRuleActions) {
        List<RuleAction> ruleActions = new ArrayList<>();

        for (ProgramRuleAction pra : programRuleActions) {
            switch (pra.programRuleActionType()) {
                case HIDEFIELD:
                    String hideField = pra.dataElement() != null ?
                            pra.dataElement().uid() : pra.trackedEntityAttribute().uid();
                    ruleActions.add(RuleActionHideField.create(pra.content(), hideField));
                    break;
                case DISPLAYTEXT:
                    ruleActions.add(RuleActionDisplayText.createForFeedback(pra.content(), pra.data()));
                    break;
                case DISPLAYKEYVALUEPAIR:
                    ruleActions.add(RuleActionDisplayKeyValuePair.createForIndicators(pra.content(), pra.data()));
                    break;
                case HIDESECTION:
                    String hideSection = pra.programStageSection() != null ?
                            pra.programStageSection().uid() : "";
                    ruleActions.add(RuleActionHideSection.create(hideSection));
                    break;

                case ASSIGN:
                    String assignData = pra.data() != null ? pra.data() : "";
                    String assignField = (pra.dataElement() != null ? pra.dataElement().uid() : (pra.trackedEntityAttribute() != null ? pra.trackedEntityAttribute().uid() : null));
                    ruleActions.add(RuleActionAssign.create(pra.content(), assignData, assignField));
                    break;
                case SHOWWARNING:
                    String warningData = pra.data() != null ? pra.data() : "";
                    String warningField = (pra.dataElement() != null ? pra.dataElement().uid() : (pra.trackedEntityAttribute() != null ? pra.trackedEntityAttribute().uid() : null));
                    ruleActions.add(RuleActionShowWarning.create(pra.content(), warningData, warningField));
                    break;
                case WARNINGONCOMPLETE:
                    String warningcompData = pra.data() != null ? pra.data() : "";
                    String warningcompField = (pra.dataElement() != null ? pra.dataElement().uid() : (pra.trackedEntityAttribute() != null ? pra.trackedEntityAttribute().uid() : null));
                    ruleActions.add(RuleActionWarningOnCompletion.create(pra.content(), warningcompData, warningcompField));
                    break;
                case SHOWERROR:
                    String showerrorData = pra.data() != null ? pra.data() : "";
                    String showerrorField = (pra.dataElement() != null ? pra.dataElement().uid() : (pra.trackedEntityAttribute() != null ? pra.trackedEntityAttribute().uid() : null));
                    ruleActions.add(RuleActionShowError.create(pra.content(), showerrorData, showerrorField));
                    break;
                case ERRORONCOMPLETE:
                    String errorcompData = pra.data() != null ? pra.data() : "";
                    String errorcompField = (pra.dataElement() != null ? pra.dataElement().uid() : (pra.trackedEntityAttribute() != null ? pra.trackedEntityAttribute().uid() : null));
                    ruleActions.add(RuleActionErrorOnCompletion.create(pra.content(), errorcompData, errorcompField));
                    break;
                case CREATEEVENT:
                    String createData = pra.data() != null ? pra.data() : "";
                    String createField = (pra.dataElement() != null ? pra.dataElement().uid() : (pra.trackedEntityAttribute() != null ? pra.trackedEntityAttribute().uid() : null));
                    ruleActions.add(RuleActionCreateEvent.create(pra.content(), createData, pra.programStage().uid()));
                    break;
                case SETMANDATORYFIELD:
                    String mandatField = (pra.dataElement() != null ? pra.dataElement().uid() : (pra.trackedEntityAttribute() != null ? pra.trackedEntityAttribute().uid() : null));
                    ruleActions.add(RuleActionSetMandatoryField.create(mandatField));
                    break;

            }
        }

        return ruleActions;
    }

    private List<RuleVariable> transformToRuleVariable(List<ProgramRuleVariable> programRuleVariables) {
        List<RuleVariable> ruleVariables = new ArrayList<>();
        for (ProgramRuleVariable prv : programRuleVariables) {

            RuleValueType mimeType = null;

            TrackedEntityAttribute attr = null;
            DataElement de = null;

            switch (prv.programRuleVariableSourceType()) {
                case TEI_ATTRIBUTE:
                    attr = d2.trackedEntityModule().trackedEntityAttributes()
                            .uid(prv.trackedEntityAttribute().uid()).blockingGet();
                    if (attr != null)
                        mimeType = convertType(attr.valueType());
                    break;
                case DATAELEMENT_CURRENT_EVENT:
                case DATAELEMENT_PREVIOUS_EVENT:
                case DATAELEMENT_NEWEST_EVENT_PROGRAM:
                case DATAELEMENT_NEWEST_EVENT_PROGRAM_STAGE:
                    de = d2.dataElementModule().dataElements().uid(prv.dataElement().uid()).blockingGet();
                    if (de != null)
                        mimeType = convertType(de.valueType());
                    break;
                default:
                    break;
            }

            if (mimeType == null) {
                mimeType = RuleValueType.TEXT;
            }
            String name = prv.name();

            switch (prv.programRuleVariableSourceType()) {
                case TEI_ATTRIBUTE:
                    ruleVariables.add(RuleVariableAttribute.create(name, attr.uid(), mimeType));
                    break;
                case DATAELEMENT_CURRENT_EVENT:
                    ruleVariables.add(RuleVariableCurrentEvent.create(name, de.uid(), mimeType));
                    break;
                case DATAELEMENT_NEWEST_EVENT_PROGRAM:
                    ruleVariables.add(RuleVariableNewestEvent.create(name, de.uid(), mimeType));
                    break;
                case DATAELEMENT_NEWEST_EVENT_PROGRAM_STAGE:
                    if (prv.programStage() != null)
                        ruleVariables.add(RuleVariableNewestStageEvent.create(name, de.uid(), prv.programStage().uid(), mimeType));
                    break;
                case DATAELEMENT_PREVIOUS_EVENT:
                    ruleVariables.add(RuleVariablePreviousEvent.create(name, de.uid(), mimeType));
                    break;
                case CALCULATED_VALUE:
                    String variable = "";
                    if (de != null || attr != null) {
                        variable = de != null ? de.uid() : attr.uid();
                    }
                    ruleVariables.add(RuleVariableCalculatedValue.create(
                            name, variable != null ? variable : "", mimeType));
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported variable " +
                            "source type: " + prv.programRuleVariableSourceType().name());
            }
        }

        return ruleVariables;
    }

    @NonNull
    private static RuleValueType convertType(@NonNull ValueType valueType) {
        if (valueType.isInteger() || valueType.isNumeric()) {
            return RuleValueType.NUMERIC;
        } else if (valueType.isBoolean()) {
            return RuleValueType.BOOLEAN;
        } else {
            return RuleValueType.TEXT;
        }
    }

    private Flowable<Map<String, List<String>>> getSupplementaryData(String orgUnitUid){
        Map<String, List<String>> supData = new HashMap<String, List<String>>();
        if (orgUnitUid!= "") {
            OrganisationUnit orgUnit = d2.organisationUnitModule().organisationUnits()
                    .withOrganisationUnitGroups().uid(orgUnitUid)
                    .blockingGet();

            Iterator orgit = orgUnit.organisationUnitGroups().iterator();
            while (orgit.hasNext()) {
                OrganisationUnitGroup orgrp = (OrganisationUnitGroup) orgit.next();
                if (orgrp.code() != null) {
                    supData.put(orgrp.code(), Arrays.asList(orgUnit.uid()));
                }
                supData.put(orgrp.uid(), Arrays.asList(orgUnit.uid()));
            }
            List<String> userRoleUids = UidsHelper.getUidsList(d2.userModule().userRoles().blockingGet());
            supData.put("USER", userRoleUids);
        }
        return Flowable.fromCallable(() -> supData);


        /*
        return Flowable.fromCallable(() ->
                d2.organisationUnitModule().organisationUnits()
                        .withOrganisationUnitGroups()
                        .byUid().eq(orgUnitUid)
                        .blockingGet())
                .flatMapIterable(orgUnits -> orgUnits)
                .map(orgUnit -> new HashMap<>()

                        //supData.put(orgUnit.code() == null ? orgUnit.uid(): orgUnit.code(), )

                );
        */
        /*
        return Flowable.fromCallable(() ->
                new HashMap<>()

        );

        */
    }

    private Map<String, String> queryConstants(){
        return new HashMap<>();
    }
}

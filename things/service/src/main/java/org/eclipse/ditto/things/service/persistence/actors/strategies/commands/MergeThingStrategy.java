/*
 * Copyright (c) 2021 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.things.service.persistence.actors.strategies.commands;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import org.apache.pekko.actor.ActorSystem;
import org.eclipse.ditto.base.model.entity.metadata.Metadata;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.base.model.headers.WithDittoHeaders;
import org.eclipse.ditto.base.model.headers.entitytag.EntityTag;
import org.eclipse.ditto.base.model.json.FieldType;
import org.eclipse.ditto.internal.utils.persistentactors.results.Result;
import org.eclipse.ditto.internal.utils.persistentactors.results.ResultFactory;
import org.eclipse.ditto.json.JsonField;
import org.eclipse.ditto.json.JsonMergePatch;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.json.JsonObjectBuilder;
import org.eclipse.ditto.json.JsonPointer;
import org.eclipse.ditto.json.JsonRuntimeException;
import org.eclipse.ditto.json.JsonValue;
import org.eclipse.ditto.placeholders.PlaceholderFactory;
import org.eclipse.ditto.placeholders.TimePlaceholder;
import org.eclipse.ditto.rql.model.ParserException;
import org.eclipse.ditto.rql.parser.RqlPredicateParser;
import org.eclipse.ditto.rql.query.filter.QueryFilterCriteriaFactory;
import org.eclipse.ditto.rql.query.things.ThingPredicateVisitor;
import org.eclipse.ditto.things.model.Thing;
import org.eclipse.ditto.things.model.ThingId;
import org.eclipse.ditto.things.model.ThingsModelFactory;
import org.eclipse.ditto.things.model.signals.commands.ThingCommandSizeValidator;
import org.eclipse.ditto.things.model.signals.commands.ThingResourceMapper;
import org.eclipse.ditto.things.model.signals.commands.exceptions.ThingMergeInvalidException;
import org.eclipse.ditto.things.model.signals.commands.modify.MergeThing;
import org.eclipse.ditto.things.model.signals.commands.modify.MergeThingResponse;
import org.eclipse.ditto.things.model.signals.events.ThingEvent;
import org.eclipse.ditto.things.model.signals.events.ThingMerged;
import org.eclipse.ditto.base.model.exceptions.InvalidRqlExpressionException;

/**
 * This strategy handles the {@link MergeThing} command for an already existing Thing.
 */
@Immutable
final class MergeThingStrategy extends AbstractThingModifyCommandStrategy<MergeThing> {

    private static final ThingResourceMapper<Thing, Optional<EntityTag>> ENTITY_TAG_MAPPER =
            ThingResourceMapper.from(EntityTagCalculator.getInstance());

    private static final TimePlaceholder TIME_PLACEHOLDER = TimePlaceholder.getInstance();

    /**
     * Constructs a new {@code MergeThingStrategy} object.
     *
     * @param actorSystem the actor system to use for loading the WoT extension.
     */
    MergeThingStrategy(final ActorSystem actorSystem) {
        super(MergeThing.class, actorSystem);
    }

    @Override
    protected Result<ThingEvent<?>> doApply(final Context<ThingId> context,
            @Nullable final Thing thing,
            final long nextRevision,
            final MergeThing command,
            @Nullable final Metadata metadata) {

        final Thing nonNullThing = getEntityOrThrow(thing);
        final Instant eventTs = getEventTimestamp();
        return handleMergeExisting(context, nonNullThing, eventTs, nextRevision, command, metadata);
    }

    @Override
    protected CompletionStage<MergeThing> performWotValidation(final MergeThing command,
            @Nullable final Thing previousThing,
            @Nullable final Thing previewThing
    ) {
        return wotThingModelValidator.validateMergeThing(
                Optional.ofNullable(previewThing).flatMap(Thing::getDefinition)
                        .or(() -> Optional.ofNullable(previousThing).flatMap(Thing::getDefinition))
                        .orElse(null),
                command,
                Optional.ofNullable(previewThing).orElseThrow(),
                command.getResourcePath(),
                command.getDittoHeaders()
        ).thenApply(aVoid -> command);
    }

    private Result<ThingEvent<?>> handleMergeExisting(final Context<ThingId> context,
            final Thing thing,
            final Instant eventTs,
            final long nextRevision,
            final MergeThing command,
            @Nullable final Metadata metadata
    ) {
        return handleMergeExistingV2WithV2Command(context, thing, eventTs, nextRevision, command, metadata);
    }

    /**
     * Handles a {@link MergeThing} command that was sent via API v2 and targets a Thing with API version V2.
     */
    private Result<ThingEvent<?>> handleMergeExistingV2WithV2Command(final Context<ThingId> context, final Thing thing,
            final Instant eventTs,
            final long nextRevision,
            final MergeThing command,
            @Nullable final Metadata metadata
    ) {
        return applyMergeCommand(context, thing, eventTs, nextRevision, command, metadata);
    }

    private Result<ThingEvent<?>> applyMergeCommand(final Context<ThingId> context,
            final Thing thing,
            final Instant eventTs,
            final long nextRevision,
            final MergeThing command,
            @Nullable final Metadata metadata
    ) {
        // make sure that the ThingMerged-Event contains all data contained in the resulting existingThing
        // (this is required e.g. for updating the search-index)
        final DittoHeaders dittoHeaders = command.getDittoHeaders();
        final JsonPointer path = command.getPath();
        final JsonValue originalValue = command.getEntity().orElseGet(command::getValue);
        final JsonValue filteredValue = evaluatePatchConditions(thing, originalValue, command);

        final Thing mergedThing = wrapException(() -> mergeThing(context, command, thing, eventTs, nextRevision, filteredValue),
                command.getDittoHeaders());

        final CompletionStage<MergeThing> validatedStage = buildValidatedStage(command, thing, mergedThing);

        final CompletionStage<ThingEvent<?>> eventStage = validatedStage.thenApply(mergeThing ->
                ThingMerged.of(mergeThing.getEntityId(), path, filteredValue, nextRevision, eventTs, dittoHeaders, metadata)
        );
        final CompletionStage<WithDittoHeaders> responseStage = validatedStage.thenApply(mergeThing ->
                appendETagHeaderIfProvided(mergeThing, MergeThingResponse.of(command.getEntityId(), path,
                                createCommandResponseDittoHeaders(dittoHeaders, nextRevision)), mergedThing)
        );
        return ResultFactory.newMutationResult(command, eventStage, responseStage);
    }

    private Thing mergeThing(final Context<ThingId> context,
            final MergeThing command,
            final Thing thing,
            final Instant eventTs,
            final long nextRevision,
            final JsonValue filteredValue
    ) {
        final JsonObject existingThingJson = thing.toJson(FieldType.all());
        
        final JsonMergePatch jsonMergePatch = JsonMergePatch.of(command.getPath(), filteredValue);
        final JsonObject mergedJson = jsonMergePatch.applyOn(existingThingJson).asObject();

        ThingCommandSizeValidator.getInstance().ensureValidSize(
                mergedJson::getUpperBoundForStringSize,
                () -> mergedJson.toString().length(),
                command::getDittoHeaders);

        context.getLog().debug("Result of JSON merge: {}", mergedJson);
        final Thing mergedThing = ThingsModelFactory.newThingBuilder(mergedJson)
                .setRevision(nextRevision)
                .setModified(eventTs).build();
        context.getLog().debug("Thing created from merged JSON: {}", mergedThing);
        return mergedThing;
    }

    /**
     * Evaluates patch conditions and filters the merge value accordingly.
     * This method applies RQL conditions to filter out parts of the merge payload
     * that should not be applied based on the current state of the Thing.
     * 
     * <p>
     * The patch conditions are provided as a JSON object where each key represents
     * a JSON pointer path and each value is an RQL expression. If the RQL expression
     * evaluates to {@code false} against the existing Thing, the corresponding part
     * of the merge payload at that path will be removed.
     * </p>
     *
     * @param existingThing the current state of the Thing
     * @param mergeValue the original merge value to be filtered
     * @param command the MergeThing command containing patch conditions
     * @return the filtered merge value with parts removed based on failed conditions
     * @since 3.8.0
     */
    JsonValue evaluatePatchConditions(final Thing existingThing,
            final JsonValue mergeValue,
            final MergeThing command) {
        
        final Optional<JsonObject> patchConditionsOpt = command.getPatchConditions();
        if (patchConditionsOpt.isEmpty()) {
            return mergeValue;
        }

        if (!mergeValue.isObject()) {
            return mergeValue;
        }

        final JsonObject patchConditions = patchConditionsOpt.get();
        final JsonObject mergeObject = mergeValue.asObject();

        final JsonObjectBuilder adjustedPayloadBuilder = mergeObject.toBuilder();

        for (final JsonField field : patchConditions) {
            final String conditionPath = field.getKeyName();
            final String conditionExpression = field.getValue().asString();

            final boolean conditionMatches = evaluateCondition(existingThing, conditionExpression, command.getDittoHeaders());
            final JsonPointer resourcePointer = JsonPointer.of(conditionPath);
            final boolean containsResource = mergeObject.getValue(resourcePointer).isPresent();

            if (!conditionMatches && containsResource) {
                adjustedPayloadBuilder.remove(resourcePointer);
            }
        }

        return adjustedPayloadBuilder.build();
    }


    private boolean evaluateCondition(final Thing existingThing,
            final String conditionExpression,
            final DittoHeaders dittoHeaders) {
        try {
            final var criteria = QueryFilterCriteriaFactory
                    .modelBased(RqlPredicateParser.getInstance())
                    .filterCriteria(conditionExpression, dittoHeaders);

            final var predicate = ThingPredicateVisitor.apply(criteria,
                    PlaceholderFactory.newPlaceholderResolver(TIME_PLACEHOLDER, new Object()));

            return predicate.test(existingThing);
        } catch (final ParserException | IllegalArgumentException e) {
            throw InvalidRqlExpressionException.newBuilder()
                    .message(e.getMessage())
                    .cause(e)
                    .dittoHeaders(dittoHeaders)
                    .build();
        }
    }

    @Override
    public Optional<EntityTag> previousEntityTag(final MergeThing command, @Nullable final Thing previousEntity) {
        return ENTITY_TAG_MAPPER.map(command.getPath(), previousEntity);
    }

    @Override
    public Optional<EntityTag> nextEntityTag(final MergeThing thingCommand, @Nullable final Thing newEntity) {
        return ENTITY_TAG_MAPPER.map(thingCommand.getPath(), getEntityOrThrow(newEntity));
    }

    private static <T> T wrapException(final Supplier<T> supplier, final DittoHeaders dittoHeaders) {
        try {
            return supplier.get();
        } catch (final JsonRuntimeException
                       | IllegalArgumentException
                       | NullPointerException
                       | UnsupportedOperationException e) {
            throw ThingMergeInvalidException.fromMessage(e.getMessage(), dittoHeaders);
        }
    }
}

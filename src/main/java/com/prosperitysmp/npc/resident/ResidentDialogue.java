package com.prosperitysmp.npc.resident;

import com.prosperitysmp.npc.property.TenantMood;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Authored dialogue over facts. Financial terms and arbitrary player text are deliberately absent. */
public final class ResidentDialogue {
    public static final int VARIANT_COUNT = 2;

    /** CONTRACTS is shown only while town.contracts or town.partnerships is on, the same way TIPS is. */
    public enum Topic { GREETING, ROUTINE, HOME, BUSINESS, COMMERCE, HISTORY, ABOUT, TIPS, CONTRACTS }
    public record Reply(String line, String evidenceRef, List<Topic> choices) {
        public Reply { choices = List.copyOf(choices); }
    }
    private ResidentDialogue() { }

    public static Reply reply(ResidentProfile person, RelationshipMemory.State relationship,
                              Topic topic, long worldTime) {
        return reply(person, relationship, topic, worldTime, 0);
    }

    /** A nonnegative session variant selects bounded authored wording, never different facts or evidence. */
    public static Reply reply(ResidentProfile person, RelationshipMemory.State relationship,
                              Topic topic, long worldTime, int variant) {
        return reply(person, relationship, topic, worldTime, variant, null);
    }

    /**
     * With owner lines from residents.yml. They replace only the first greeting to a stranger, the trade sentence in
     * ABOUT (the household sentence is still added by code) and the HOME line. Memory greetings, ROUTINE, BUSINESS,
     * HISTORY, COMMERCE and TIPS state facts, so they always use the code's own lines. Null means no authored lines.
     */
    public static Reply reply(ResidentProfile person, RelationshipMemory.State relationship,
                              Topic topic, long worldTime, int variant, ResidentLines lines) {
        return reply(person, relationship, topic, worldTime, variant, lines, false);
    }

    /**
     * partnersOn (town.partnerships on): the BUSINESS line points at the contracts topic instead of saying there are
     * no offers. Off, every line is byte-identical to the overload without it.
     */
    public static Reply reply(ResidentProfile person, RelationshipMemory.State relationship,
                              Topic topic, long worldTime, int variant, ResidentLines lines, boolean partnersOn) {
        ResidentLines authored = lines == null ? ResidentLines.NONE : lines;
        if (variant < 0) {
            throw new IllegalArgumentException("Dialogue variant must be nonnegative");
        }
        if (!person.id().equals(relationship.residentId())) {
            throw new IllegalArgumentException("Dialogue belongs to another resident");
        }
        String line;
        String evidence = null;
        switch (topic) {
            case GREETING -> {
                // Only the most recent meaningful event speaks, so old praise cannot overwrite a newer failure.
                var memory = latestMeaningful(relationship);
                if (memory.isPresent()) {
                    var e = memory.get();
                    line = memoryGreeting(person.temperament(), e.kind(), variant);
                    evidence = e.sourceRef();
                } else if (relationship.stage() != RelationshipMemory.Stage.STRANGER) {
                    line = switch (person.temperament()) {
                        case RESERVED -> choose(variant,
                                "Hello again. Is there something you'd like to talk about?",
                                "Good to see you again. I have a moment to talk.");
                        case WARM -> choose(variant,
                                "Good to see you again. What brings you by?",
                                "Hello again. It's good to have someone to chat with.");
                        case DIRECT -> choose(variant,
                                "We've met. What would you like to discuss?",
                                "Hello again. What's on your mind?");
                        case INQUISITIVE -> choose(variant,
                                "Hello again. What have you been curious about lately?",
                                "Good to see you again. What caught your interest today?");
                    };
                } else if (!authored.greeting().isEmpty()) {
                    line = authored.greeting(variant, person.name()).orElseThrow();
                } else {
                    line = switch (person.temperament()) {
                        case RESERVED -> choose(variant,
                                "Hello. I'm " + person.name() + ". Settling in, or just passing through?",
                                "I'm " + person.name() + ". Hello. Would you like to talk for a moment?");
                        case WARM -> choose(variant,
                                "I'm " + person.name() + ". Always good to meet a neighbor.",
                                "Hello! I'm " + person.name() + ". What brings you to the neighborhood?");
                        case DIRECT -> choose(variant,
                                person.name() + ". Good to meet you. What can I help with?",
                                "I'm " + person.name() + ". What would you like to talk about?");
                        case INQUISITIVE -> choose(variant,
                                "I'm " + person.name() + ". Have you found a favorite place around here yet?",
                                "I'm " + person.name() + ". What brought you here today?");
                    };
                }
            }
            case ABOUT -> line = about(person, variant, authored);
            case COMMERCE -> line = choose(variant, "Let's check supplies and hired work against my order book.",
                    "We can look through the order book for supplies and hired work.");
            case ROUTINE -> line = switch (ResidentRoutine.at(person, worldTime).activity()) {
                case HOME -> choose(variant, "This is my quiet time. A short conversation is welcome.",
                        "I'm taking some quiet time. What's on your mind?");
                case WORK -> choose(variant, "It is my work time now. I can stop for a moment.",
                        "This is my work time. What did you want to talk about?");
                case ERRAND -> choose(variant, "I have a few errands on my list before I settle down.",
                        "It's my errand time. A quick chat is welcome.");
                case LEISURE -> choose(variant, "The workday is behind me. Time to enjoy the neighborhood.",
                        "This is my time to unwind. What's on your mind?");
            };
            case HOME -> line = !authored.home().isEmpty() ? authored.home(variant, person.name()).orElseThrow()
                    : switch (person.preference()) {
                case WELL_KEPT_HOME -> choose(variant,
                        "I value a place that is cared for. Small repairs should not become big problems.",
                        "I like a home where upkeep gets attention before things fall apart.");
                case CLEAR_TERMS -> choose(variant,
                        "Tell me what a place costs and what is included. Clear terms come first.",
                        "I value knowing what is included before making up my mind about a home.");
                case QUIET_ROUTINE -> choose(variant,
                        "A dependable, quiet place suits me better than constant change.",
                        "I like having a calm place and a routine I can count on.");
                case NEIGHBORHOOD_LIFE -> choose(variant,
                        "A good home is also a neighborhood where you get to know people.",
                        "I enjoy a neighborhood where familiar faces stop to say hello.");
            };
            case BUSINESS -> line = partnersOn ? businessWithPartners(relationship)
                    : relationship.stage().ordinal() >= RelationshipMemory.Stage.TRUSTED.ordinal()
                    ? choose(variant, "We have a good history. There are no partnership offers available just now.",
                            "I value the history we've built. There are no business offers available.")
                    : choose(variant, "I prefer to know people before discussing business. There are no offers available.",
                            "I'm still getting to know you. There are no business offers available.");
            case HISTORY -> {
                var memory = latestMeaningful(relationship);
                if (memory.isPresent()) {
                    var event = memory.get();
                    line = memoryGreeting(person.temperament(), event.kind(), variant) + " Service day: " + event.economyDay() + ".";
                    evidence = event.sourceRef();
                } else if (relationship.stage() == RelationshipMemory.Stage.STRANGER) {
                    line = choose(variant, "Let's start with hello. I'm glad you stopped by.",
                            "It's good to see you. Shall we introduce ourselves?");
                } else {
                    line = choose(variant, "I don't have a recent service memory to recall.",
                            "There's no recent service event in my records to look back on.");
                }
            }
            case TIPS -> line = choose(variant, "Let me think of something useful.", "Here is something worth knowing.");
            case CONTRACTS -> line = choose(variant, "Let's look at contracts and partners.",
                    "Here is what I have for contracts and partners.");
            default -> throw new IllegalArgumentException("Unknown topic");
        }
        return new Reply(line, evidence, List.of(Topic.ROUTINE, Topic.HOME, Topic.HISTORY, Topic.BUSINESS, Topic.COMMERCE, Topic.ABOUT));
    }

    /** The BUSINESS line while partnerships are on. */
    public static String businessWithPartners(RelationshipMemory.State relationship) {
        return relationship.stage().ordinal() >= RelationshipMemory.Stage.TRUSTED.ordinal()
                ? "We have a good history. Ask me about contracts and partners."
                : "I'm still getting to know you. Ask me about contracts.";
    }

    /** One short authored line per mood band. States how the resident feels now, never a past event. */
    public static String moodLine(TenantMood.Band band) {
        return switch (band) {
            case UNHAPPY -> "I'm not happy with my home right now.";
            case NEUTRAL -> "My home is fine. Nothing special either way.";
            case CONTENT -> "I'm content with my home these days.";
            case HAPPY -> "I'm very happy with my home right now.";
        };
    }

    /** Character background is not a service memory, ownership claim, or family relationship. */
    private static String about(ResidentProfile person, int variant, ResidentLines authored) {
        String work = !authored.about().isEmpty() ? authored.about(variant, person.name()).orElseThrow()
                : switch (person.occupation()) {
            case RESTORER -> choose(variant, "I'm a restorer. I like finding what is worth keeping in an old build.",
                    "Restoration is my trade. A well-kept detail means more to me than a grand facade.");
            case SHOPKEEPER -> choose(variant, "I'm a shopkeeper by trade. Clear terms make a good start to any deal.",
                    "My trade is shopkeeping. I like knowing exactly what both sides have agreed to.");
            case GARDENER -> choose(variant, "I'm a gardener. I like a place with room for something to grow.",
                    "Gardening is my trade. Small changes can make a place feel quite different.");
            case COURIER -> choose(variant, "I'm a courier by trade. I enjoy getting to know a neighborhood.",
                    "My trade is courier work. Familiar streets and familiar faces appeal to me.");
            case COOK -> choose(variant, "I'm a cook. I like the care that goes into making something well.",
                    "Cooking is my trade. Simple things done thoughtfully suit me.");
            case CARPENTER -> choose(variant, "I'm a carpenter. I appreciate a good fit and a sturdy finish.",
                    "Carpentry is my trade. Something practical can be beautiful too.");
        };
        return work + " I'm part of the " + person.household() + " household.";
    }

    private static Optional<RelationshipMemory.Event> latestMeaningful(RelationshipMemory.State state) {
        return state.recent().stream().filter(e -> e.kind() != RelationshipMemory.Kind.INTRODUCTION)
                .max(Comparator.comparing(RelationshipMemory.Event::occurredAt).thenComparing(RelationshipMemory.Event::sourceRef));
    }

    private static String memoryGreeting(ResidentProfile.Temperament temperament, RelationshipMemory.Kind kind, int variant) {
        return switch (kind) {
            case REPAIR_COMPLETED -> switch (temperament) {
                case RESERVED -> choose(variant, "I noticed the repair was completed. Thank you.",
                        "The repair is done. I appreciate you seeing it through.");
                case WARM -> choose(variant, "Thanks for seeing that repair through. I remember who follows up.",
                        "I'm glad you saw that repair through. Thank you for following up.");
                case DIRECT -> choose(variant, "You completed the repair. I appreciate the follow-through.",
                        "That repair is complete. Thank you for getting it done.");
                case INQUISITIVE -> choose(variant, "You saw that repair through. How did it go?",
                        "I remember the repair you completed. Was it straightforward?");
            };
            case OBLIGATION_KEPT -> switch (temperament) {
                case RESERVED -> choose(variant, "I remember you kept your word. I appreciate that.",
                        "You followed through on that obligation. Thank you.");
                case WARM -> choose(variant, "You kept your word last time. That matters to me.",
                        "It was good to see you keep your word. Thank you.");
                case DIRECT -> choose(variant, "You met that obligation. That's the follow-through I value.",
                        "You kept your word. I noticed.");
                case INQUISITIVE -> choose(variant, "You kept your word on that obligation. How did it go?",
                        "I remember you followed through. What helped you see it through?");
            };
            case OBLIGATION_BROKEN -> switch (temperament) {
                case RESERVED -> choose(variant, "I remember that obligation was not met. I'd rather be clear about it.",
                        "That obligation wasn't met. I haven't forgotten.");
                case WARM -> choose(variant, "Our last arrangement did not work out. I'd like clearer terms next time.",
                        "I'm sorry that obligation wasn't met. I'd like to understand what happened.");
                case DIRECT -> choose(variant, "That obligation was not met. We need to be clear about that.",
                        "You didn't meet that obligation. I remember it.");
                case INQUISITIVE -> choose(variant, "That obligation wasn't met. What happened?",
                        "I remember the obligation that fell through. What got in the way?");
            };
            case INTRODUCTION -> throw new IllegalArgumentException("Introduction is not an outcome callback");
        };
    }

    private static String choose(int variant, String first, String second) {
        return variant % VARIANT_COUNT == 0 ? first : second;
    }
}

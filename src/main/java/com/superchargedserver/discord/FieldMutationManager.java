package com.superchargedserver.discord;

import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.SelectOption;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;

import java.util.ArrayList;
import java.util.List;

/**
 * The advanced field mutation matrix for the embed builder: select a field
 * by index, then either overwrite it through a pre-filled modal or append
 * new text onto the existing content with a configurable delimiter — all
 * while enforcing Discord's 1,024-character field value limit.
 */
public class FieldMutationManager {

    private static final int MAX_FIELD_VALUE = 1024;

    /** String select enumerating every field currently saved to the draft. */
    public StringSelectMenu buildFieldSelect(DraftSession session) {
        List<SelectOption> options = new ArrayList<>();
        List<DraftSession.Field> fields = session.getFields();
        for (int i = 0; i < fields.size(); i++) {
            String label = (i + 1) + ". " + fields.get(i).title();
            options.add(SelectOption.of(label.length() > 100 ? label.substring(0, 97) + "..." : label,
                    String.valueOf(i)));
        }
        return StringSelectMenu.create("sc-eb:editfields-pick")
                .setPlaceholder("✏️ Pick a field to edit…")
                .addOptions(options)
                .build();
    }

    /** Turns the picker message into the mutation sub-panel for the chosen field. */
    public void handleFieldPick(StringSelectInteractionEvent event, DraftSession session) {
        int index;
        try {
            index = Integer.parseInt(event.getValues().get(0));
        } catch (NumberFormatException ex) {
            return;
        }
        if (index < 0 || index >= session.getFields().size()) {
            event.editMessage("⌛ That field no longer exists.").setComponents().queue();
            return;
        }
        DraftSession.Field field = session.getFields().get(index);
        event.editMessage("✏️ Editing field **" + (index + 1) + ". " + field.title() + "** — "
                        + field.value().length() + " / " + MAX_FIELD_VALUE + " characters.")
                .setComponents(ActionRow.of(
                        Button.primary("sc-eb:mut:ow:" + index, "Overwrite / Replace Content"),
                        Button.secondary("sc-eb:mut:ap:" + index, "Append / Add Content"),
                        Button.danger("sc-eb:mut:close:0", "Close")))
                .queue();
    }

    /** Routes {@code sc-eb:mut:<ow|ap|close>:<index>} clicks to the matching modal. */
    public void handleMutationButton(ButtonInteractionEvent event, DraftSession session) {
        String[] parts = event.getComponentId().split(":");
        if (parts.length != 4) return;
        if (parts[2].equals("close")) {
            event.editMessage("✅ Field editor closed.").setComponents().queue();
            return;
        }
        int index;
        try {
            index = Integer.parseInt(parts[3]);
        } catch (NumberFormatException ex) {
            return;
        }
        if (index < 0 || index >= session.getFields().size()) {
            event.editMessage("⌛ That field no longer exists.").setComponents().queue();
            return;
        }
        DraftSession.Field field = session.getFields().get(index);
        switch (parts[2]) {
            case "ow" -> event.replyModal(overwriteModal(index, field)).queue();
            case "ap" -> event.replyModal(appendModal(index, field)).queue();
        }
    }

    private Modal overwriteModal(int index, DraftSession.Field field) {
        TextInput.Builder title = TextInput.create("title", "Field Title", TextInputStyle.SHORT)
                .setRequired(true)
                .setMaxLength(256);
        if (!field.title().isBlank()) title.setValue(field.title());
        TextInput.Builder value = TextInput.create("value", "Field Value / Text", TextInputStyle.PARAGRAPH)
                .setRequired(true)
                .setMaxLength(MAX_FIELD_VALUE);
        if (!field.value().isBlank()) value.setValue(field.value());
        return Modal.create("sc-eb-m:mut-ow:" + index, "Field — Overwrite Content")
                .addComponents(ActionRow.of(title.build()), ActionRow.of(value.build()))
                .build();
    }

    private Modal appendModal(int index, DraftSession.Field field) {
        int remaining = Math.max(1, MAX_FIELD_VALUE - field.value().length() - 1);
        return Modal.create("sc-eb-m:mut-ap:" + index, "Field — Append Content")
                .addComponents(
                        ActionRow.of(TextInput.create("text", "Text to Append", TextInputStyle.PARAGRAPH)
                                .setRequired(true)
                                .setMaxLength(Math.min(MAX_FIELD_VALUE, remaining))
                                .setPlaceholder("Stitched onto the end of the existing content")
                                .build()),
                        ActionRow.of(TextInput.create("delimiter", "Delimiter (blank = new line)", TextInputStyle.SHORT)
                                .setRequired(false)
                                .setMaxLength(16)
                                .setPlaceholder("\\n")
                                .build()))
                .build();
    }

    /** Applies {@code sc-eb-m:mut-<ow|ap>:<index>} modal submissions to the session. */
    public void handleMutationModal(ModalInteractionEvent event, DraftSession session) {
        String[] parts = event.getModalId().split(":");
        if (parts.length != 3) return;
        boolean overwrite = parts[1].equals("mut-ow");
        int index;
        try {
            index = Integer.parseInt(parts[2]);
        } catch (NumberFormatException ex) {
            return;
        }
        if (index < 0 || index >= session.getFields().size()) {
            event.editMessage("⌛ That field no longer exists.").setComponents().queue();
            return;
        }
        DraftSession.Field field = session.getFields().get(index);

        if (overwrite) {
            String title = value(event, "title");
            String newValue = value(event, "value");
            session.getFields().set(index, new DraftSession.Field(title, newValue, field.inline()));
            event.editMessage("✅ Field **" + (index + 1) + ". " + title + "** overwritten ("
                            + newValue.length() + " / " + MAX_FIELD_VALUE
                            + " chars). The dashboard preview refreshes on your next action there.")
                    .setComponents().queue();
            return;
        }

        String text = value(event, "text");
        String delimiter = value(event, "delimiter");
        delimiter = delimiter.isEmpty() ? "\n" : delimiter.replace("\\n", "\n").replace("\\t", "\t");
        String combined = field.value() + delimiter + text;
        if (combined.length() > MAX_FIELD_VALUE) {
            event.reply("❌ Appending would exceed the " + MAX_FIELD_VALUE + "-character field limit ("
                    + combined.length() + " chars).").setEphemeral(true).queue();
            return;
        }
        session.getFields().set(index, new DraftSession.Field(field.title(), combined, field.inline()));
        event.editMessage("✅ Appended to field **" + (index + 1) + ". " + field.title() + "** ("
                        + combined.length() + " / " + MAX_FIELD_VALUE
                        + " chars). The dashboard preview refreshes on your next action there.")
                .setComponents().queue();
    }

    private String value(ModalInteractionEvent event, String id) {
        return event.getValue(id) == null ? "" : event.getValue(id).getAsString().trim();
    }
}
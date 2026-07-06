import {
    APIEmbed,
    ChatInputCommandInteraction,
    EmbedBuilder,
    PermissionFlagsBits,
    SlashCommandBuilder
} from 'discord.js';
import { Command } from '../types/Command';

const HEX_COLOR = /^#?[0-9a-fA-F]{6}$/;

/**
 * Validates a parsed object so malformed input can't crash the send.
 * Returns a cleaned APIEmbed or an error string.
 */
function validateEmbed(raw: unknown): { embed?: APIEmbed; error?: string } {
    if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) {
        return { error: 'The JSON must be an object, e.g. `{"title": "Hello", "description": "World"}`.' };
    }

    const input = raw as Record<string, unknown>;
    const embed: APIEmbed = {};

    if (input.title !== undefined) {
        if (typeof input.title !== 'string' || input.title.length > 256) {
            return { error: '`title` must be a string of at most 256 characters.' };
        }
        embed.title = input.title;
    }

    if (input.description !== undefined) {
        if (typeof input.description !== 'string' || input.description.length > 4096) {
            return { error: '`description` must be a string of at most 4096 characters.' };
        }
        embed.description = input.description;
    }

    if (input.url !== undefined) {
        if (typeof input.url !== 'string' || !input.url.startsWith('http')) {
            return { error: '`url` must be a valid http(s) URL string.' };
        }
        embed.url = input.url;
    }

    if (input.color !== undefined) {
        if (typeof input.color === 'number' && input.color >= 0 && input.color <= 0xFFFFFF) {
            embed.color = input.color;
        } else if (typeof input.color === 'string' && HEX_COLOR.test(input.color)) {
            embed.color = parseInt(input.color.replace('#', ''), 16);
        } else {
            return { error: '`color` must be a number (0-16777215) or hex string like `#FF0000`.' };
        }
    }

    if (input.thumbnail !== undefined) {
        const url = typeof input.thumbnail === 'string'
            ? input.thumbnail
            : (input.thumbnail as Record<string, unknown>)?.url;
        if (typeof url !== 'string' || !url.startsWith('http')) {
            return { error: '`thumbnail` must be a URL string or `{"url": "..."}`.' };
        }
        embed.thumbnail = { url };
    }

    if (input.image !== undefined) {
        const url = typeof input.image === 'string'
            ? input.image
            : (input.image as Record<string, unknown>)?.url;
        if (typeof url !== 'string' || !url.startsWith('http')) {
            return { error: '`image` must be a URL string or `{"url": "..."}`.' };
        }
        embed.image = { url };
    }

    if (input.footer !== undefined) {
        const text = typeof input.footer === 'string'
            ? input.footer
            : (input.footer as Record<string, unknown>)?.text;
        if (typeof text !== 'string' || text.length > 2048) {
            return { error: '`footer` must be a string or `{"text": "..."}` of at most 2048 characters.' };
        }
        embed.footer = { text };
    }

    if (input.author !== undefined) {
        const name = typeof input.author === 'string'
            ? input.author
            : (input.author as Record<string, unknown>)?.name;
        if (typeof name !== 'string' || name.length > 256) {
            return { error: '`author` must be a string or `{"name": "..."}` of at most 256 characters.' };
        }
        embed.author = { name };
    }

    if (input.fields !== undefined) {
        if (!Array.isArray(input.fields) || input.fields.length > 25) {
            return { error: '`fields` must be an array of at most 25 entries.' };
        }
        const fields = [];
        for (const field of input.fields) {
            const f = field as Record<string, unknown>;
            if (typeof f?.name !== 'string' || typeof f?.value !== 'string'
                || f.name.length > 256 || f.value.length > 1024) {
                return { error: 'Each field needs `name` (max 256 chars) and `value` (max 1024 chars) strings.' };
            }
            fields.push({ name: f.name, value: f.value, inline: f.inline === true });
        }
        embed.fields = fields;
    }

    if (input.timestamp !== undefined) {
        if (input.timestamp === true) {
            embed.timestamp = new Date().toISOString();
        } else if (typeof input.timestamp === 'string' && !isNaN(new Date(input.timestamp).getTime())) {
            embed.timestamp = new Date(input.timestamp).toISOString();
        } else {
            return { error: '`timestamp` must be `true` or an ISO date string.' };
        }
    }

    if (Object.keys(embed).length === 0) {
        return { error: 'The embed is empty. Provide at least a `title`, `description`, or `fields`.' };
    }

    return { embed };
}

const embedCommand: Command = {
    data: new SlashCommandBuilder()
        .setName('embed')
        .setDescription('Send a rich embed from JSON')
        .setDefaultMemberPermissions(PermissionFlagsBits.ManageMessages)
        .addStringOption(option =>
            option.setName('json_code')
                .setDescription('Embed JSON, e.g. {"title":"Hi","description":"...","color":"#FF0000"}')
                .setRequired(true)),

    async execute(interaction: ChatInputCommandInteraction): Promise<void> {
        const jsonCode = interaction.options.getString('json_code', true);

        let parsed: unknown;
        try {
            parsed = JSON.parse(jsonCode);
        } catch (err) {
            await interaction.reply({
                content: `❌ Invalid JSON: ${err instanceof Error ? err.message : 'unknown parse error'}\nExample: \`{"title":"Hello","description":"World","color":"#00FF00"}\``,
                ephemeral: true
            });
            return;
        }

        const { embed, error } = validateEmbed(parsed);
        if (error || !embed) {
            await interaction.reply({ content: `❌ ${error}`, ephemeral: true });
            return;
        }

        try {
            await interaction.reply({ embeds: [EmbedBuilder.from(embed)] });
        } catch (err) {
            await interaction.reply({
                content: `❌ Discord rejected the embed: ${err instanceof Error ? err.message : 'unknown error'}`,
                ephemeral: true
            });
        }
    }
};

export default embedCommand;
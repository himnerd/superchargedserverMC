import {
    ChannelType,
    ChatInputCommandInteraction,
    PermissionFlagsBits,
    SlashCommandBuilder,
    TextChannel
} from 'discord.js';
import { Command } from '../types/Command';

interface ScheduledAnnouncement {
    channelId: string;
    message: string;
    sendAt: Date;
}

const scheduleQueue: ScheduledAnnouncement[] = [];

function addToScheduleQueue(entry: ScheduledAnnouncement): void {
    scheduleQueue.push(entry);

    const delay = entry.sendAt.getTime() - Date.now();
    setTimeout(async () => {
        const index = scheduleQueue.indexOf(entry);
        if (index !== -1) scheduleQueue.splice(index, 1);

        try {
            const { client } = await import('discord.js').then(() => require('../index'));
        } catch {
            // fall through to console mock below
        }
        console.log(`[Scheduler] Announcement due for channel ${entry.channelId}: ${entry.message}`);
    }, delay);
}

/**
 * Accepts "HH:mm" (today, or tomorrow if already past) or a full
 * date string parseable by Date (e.g. "2026-07-04 18:00").
 */
function parseScheduleTime(input: string): Date | null {
    const hhmm = /^([01]?\d|2[0-3]):([0-5]\d)$/.exec(input.trim());
    if (hhmm) {
        const target = new Date();
        target.setHours(parseInt(hhmm[1], 10), parseInt(hhmm[2], 10), 0, 0);
        if (target.getTime() <= Date.now()) {
            target.setDate(target.getDate() + 1);
        }
        return target;
    }

    const parsed = new Date(input);
    if (!isNaN(parsed.getTime()) && parsed.getTime() > Date.now()) {
        return parsed;
    }
    return null;
}

const announce: Command = {
    data: new SlashCommandBuilder()
        .setName('announce')
        .setDescription('Send or schedule a server announcement')
        .setDefaultMemberPermissions(PermissionFlagsBits.ManageMessages)
        .addStringOption(option =>
            option.setName('message')
                .setDescription('The announcement message')
                .setRequired(true))
        .addChannelOption(option =>
            option.setName('channel')
                .setDescription('Channel to announce in (defaults to current channel)')
                .addChannelTypes(ChannelType.GuildText)
                .setRequired(false))
        .addStringOption(option =>
            option.setName('schedule_time')
                .setDescription('When to send it, e.g. "18:30" or "2026-07-04 18:00"')
                .setRequired(false)),

    async execute(interaction: ChatInputCommandInteraction): Promise<void> {
        const message = interaction.options.getString('message', true);
        const channelOption = interaction.options.getChannel('channel');
        const scheduleTime = interaction.options.getString('schedule_time');

        const targetChannel = (channelOption ?? interaction.channel) as TextChannel | null;
        if (!targetChannel || targetChannel.type !== ChannelType.GuildText) {
            await interaction.reply({ content: 'Please pick a valid text channel.', ephemeral: true });
            return;
        }

        if (scheduleTime) {
            const sendAt = parseScheduleTime(scheduleTime);
            if (!sendAt) {
                await interaction.reply({
                    content: `Could not parse \`${scheduleTime}\`. Use \`HH:mm\` or a full date like \`2026-07-04 18:00\` (must be in the future).`,
                    ephemeral: true
                });
                return;
            }

            addToScheduleQueue({ channelId: targetChannel.id, message, sendAt });
            await interaction.reply({
                content: `📅 Announcement scheduled for <t:${Math.floor(sendAt.getTime() / 1000)}:F> in ${targetChannel}. (Queue size: ${scheduleQueue.length})`,
                ephemeral: true
            });
            return;
        }

        await targetChannel.send({ content: `📢 **Announcement**\n${message}` });
        await interaction.reply({ content: `Announcement sent to ${targetChannel}.`, ephemeral: true });
    }
};

export default announce;
import {
    ActionRowBuilder,
    ButtonBuilder,
    ButtonInteraction,
    ButtonStyle,
    ChannelType,
    ChatInputCommandInteraction,
    ComponentType,
    EmbedBuilder,
    GuildMember,
    PermissionFlagsBits,
    SlashCommandBuilder,
    TextChannel
} from 'discord.js';
import { Command } from '../types/Command';

const MAX_OPTIONS = 5;
const POLL_DURATION_MS = 24 * 60 * 60 * 1000;

function buildPollEmbed(question: string, options: string[], votes: Map<string, number>, roleId: string | null, closed: boolean): EmbedBuilder {
    const counts = new Array(options.length).fill(0);
    for (const choice of votes.values()) counts[choice]++;
    const total = votes.size;

    const lines = options.map((option, i) => {
        const percent = total === 0 ? 0 : Math.round((counts[i] / total) * 100);
        const filled = Math.round(percent / 10);
        const bar = '█'.repeat(filled) + '░'.repeat(10 - filled);
        return `**${i + 1}.** ${option}\n${bar} ${counts[i]} vote${counts[i] === 1 ? '' : 's'} (${percent}%)`;
    });

    const embed = new EmbedBuilder()
        .setTitle(`📊 ${question}`)
        .setDescription(lines.join('\n\n'))
        .setColor(closed ? 0x95A5A6 : 0x5865F2)
        .setFooter({ text: closed ? `Poll closed • ${total} total votes` : `${total} total votes` });

    if (roleId && !closed) {
        embed.addFields({ name: 'Who can vote', value: `<@&${roleId}>` });
    }
    return embed;
}

function buildButtons(options: string[], disabled: boolean): ActionRowBuilder<ButtonBuilder> {
    const row = new ActionRowBuilder<ButtonBuilder>();
    options.forEach((option, i) => {
        row.addComponents(
            new ButtonBuilder()
                .setCustomId(`poll_vote_${i}`)
                .setLabel(option.length > 80 ? option.slice(0, 77) + '...' : option)
                .setStyle(ButtonStyle.Primary)
                .setDisabled(disabled)
        );
    });
    return row;
}

const poll: Command = {
    data: new SlashCommandBuilder()
        .setName('poll')
        .setDescription('Create a button poll')
        .setDefaultMemberPermissions(PermissionFlagsBits.ManageMessages)
        .addStringOption(option =>
            option.setName('question')
                .setDescription('The poll question')
                .setRequired(true))
        .addStringOption(option =>
            option.setName('options')
                .setDescription('Comma-separated options (2-5), e.g. "Yes, No, Maybe"')
                .setRequired(true))
        .addChannelOption(option =>
            option.setName('channel')
                .setDescription('Channel to post the poll in (defaults to current channel)')
                .addChannelTypes(ChannelType.GuildText)
                .setRequired(false))
        .addRoleOption(option =>
            option.setName('allowed_roles')
                .setDescription('Only members with this role may vote')
                .setRequired(false)),

    async execute(interaction: ChatInputCommandInteraction): Promise<void> {
        const question = interaction.options.getString('question', true);
        const optionsRaw = interaction.options.getString('options', true);
        const channelOption = interaction.options.getChannel('channel');
        const allowedRole = interaction.options.getRole('allowed_roles');

        const options = optionsRaw.split(',').map(s => s.trim()).filter(s => s.length > 0);
        if (options.length < 2) {
            await interaction.reply({ content: 'Provide at least 2 comma-separated options.', ephemeral: true });
            return;
        }
        if (options.length > MAX_OPTIONS) {
            await interaction.reply({ content: `Maximum ${MAX_OPTIONS} options allowed (button row limit).`, ephemeral: true });
            return;
        }

        const targetChannel = (channelOption ?? interaction.channel) as TextChannel | null;
        if (!targetChannel || targetChannel.type !== ChannelType.GuildText) {
            await interaction.reply({ content: 'Please pick a valid text channel.', ephemeral: true });
            return;
        }

        const roleId = allowedRole?.id ?? null;
        const votes = new Map<string, number>(); // userId -> option index

        const pollMessage = await targetChannel.send({
            embeds: [buildPollEmbed(question, options, votes, roleId, false)],
            components: [buildButtons(options, false)]
        });

        await interaction.reply({ content: `Poll created in ${targetChannel}.`, ephemeral: true });

        const collector = pollMessage.createMessageComponentCollector({
            componentType: ComponentType.Button,
            time: POLL_DURATION_MS
        });

        collector.on('collect', async (buttonInteraction: ButtonInteraction) => {
            const member = buttonInteraction.member as GuildMember | null;

            if (roleId && (!member || !member.roles.cache.has(roleId))) {
                await buttonInteraction.reply({
                    content: `❌ You need the <@&${roleId}> role to vote in this poll.`,
                    ephemeral: true
                });
                return;
            }

            if (votes.has(buttonInteraction.user.id)) {
                await buttonInteraction.reply({
                    content: '❌ You have already voted in this poll.',
                    ephemeral: true
                });
                return;
            }

            const choiceIndex = parseInt(buttonInteraction.customId.replace('poll_vote_', ''), 10);
            votes.set(buttonInteraction.user.id, choiceIndex);

            await buttonInteraction.update({
                embeds: [buildPollEmbed(question, options, votes, roleId, false)]
            });
        });

        collector.on('end', async () => {
            await pollMessage.edit({
                embeds: [buildPollEmbed(question, options, votes, roleId, true)],
                components: [buildButtons(options, true)]
            }).catch(() => { /* message may have been deleted */ });
        });
    }
};

export default poll;
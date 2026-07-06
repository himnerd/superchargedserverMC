import { Client, Collection, Events, GatewayIntentBits, REST, Routes } from 'discord.js';
import { config } from 'dotenv';
import { Command } from './types/Command';
import announce from './commands/announce';
import embed from './commands/embed';
import poll from './commands/poll';

config();

const client = new Client({
    intents: [GatewayIntentBits.Guilds]
});

const commands = new Collection<string, Command>();
for (const command of [announce, embed, poll]) {
    commands.set(command.data.name, command);
}

client.once(Events.ClientReady, async (readyClient) => {
    console.log(`Logged in as ${readyClient.user.tag}`);

    const rest = new REST().setToken(process.env.DISCORD_TOKEN!);
    await rest.put(
        Routes.applicationCommands(readyClient.user.id),
        { body: commands.map(cmd => cmd.data.toJSON()) }
    );
    console.log(`Registered ${commands.size} slash commands.`);
});

client.on(Events.InteractionCreate, async (interaction) => {
    if (!interaction.isChatInputCommand()) return;

    const command = commands.get(interaction.commandName);
    if (!command) return;

    try {
        await command.execute(interaction);
    } catch (error) {
        console.error(`Error executing /${interaction.commandName}:`, error);
        const reply = { content: 'There was an error while executing this command.', ephemeral: true };
        if (interaction.replied || interaction.deferred) {
            await interaction.followUp(reply);
        } else {
            await interaction.reply(reply);
        }
    }
});

client.login(process.env.DISCORD_TOKEN);
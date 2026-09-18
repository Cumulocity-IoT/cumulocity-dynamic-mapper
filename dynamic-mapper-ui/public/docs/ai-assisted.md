---
title: AI-assisted mapping
---

The Dynamic Mapper is **AI empowered**.

The Dynamic Mapper can leverage AI to automatically generate mapping rules from your payload examples. Simply
describe what you want to achieve in natural language, and the AI generates the corresponding JSONata expressions
or JavaScript code.

In the **Service Configuration → AI** section, assign an AI agent (defined in the Cumulocity AI Agent Manager)
separately for JSONata, JavaScript, and Smart Function transformations. This significantly accelerates the
integration process.

When you configure AI agents in **Service Configuration → AI**, substitutions or JavaScript code can be generated
automatically. Each transformation type (JSONata, JavaScript, Smart Function) can use a different agent. The
underlying AI provider (Anthropic, OpenAI, etc.) is configured in the AI Agent Manager, not here. The use and
configuration of the Cumulocity AI Agent Manager is introduced
[here](https://community.cumulocity.com/t/introducing-the-ai-agent-manager-powering-enterprise-aiot-on-cumulocity/12567).
The following screenshot shows a prompt to generate a list of substitutions (mapping rules):

![Substitution annotation](../../../resources/image/Dynamic_Mapper_Mapping_Stepper_Substitution_Generate_JSONata.png "Screenshot showing a prompt to generate a list of substitutions (mapping rules).")


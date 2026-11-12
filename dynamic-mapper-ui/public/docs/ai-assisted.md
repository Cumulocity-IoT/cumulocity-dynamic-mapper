---
title: AI-assisted mapping
---

The Dynamic Mapper can generate Smart Function code, or JSONata substitutions, from your source and
target templates. You describe in natural language what the mapping must do, review the result, and save it.

#### Requirements

- The Cumulocity AI Agent Manager is deployed in your tenant, and its AI capabilities are not disabled. The
  underlying AI provider (Anthropic, OpenAI, and others) is configured in the AI Agent Manager, not in the Dynamic
  Mapper. The Agent Manager is introduced in the
  [Cumulocity community](https://community.cumulocity.com/t/introducing-the-ai-agent-manager-powering-enterprise-aiot-on-cumulocity/12567).
- Under **Configuration → Service configuration → AI Agent**, set **SmartFunction AI Agent** and, for JSONata,
  **JSONata AI Agent** to the names of deployed agents. Each transformation type can use a different agent.
- You need the `ROLE_DYNAMIC_MAPPER_CREATE` or `ROLE_DYNAMIC_MAPPER_ADMIN` role to edit mappings.

If an agent is missing, the **Add mapping** dialog and the **Generate with AI** button explain why AI generation is
unavailable. Mapping creation works without AI.

#### How to use it

1. In **Add mapping**, choose **Generate with AI** under **Smart Function Source** (for JSONata, **Substitutions
   Source**). When an agent is deployed, this is the default. The alternative is **Choose Code Template**.
2. Define the source and target templates in the wizard.
3. In the **Transformation** step, the assistant opens in a chat drawer and may ask whether it should propose a target structure
   or use a sample target payload from you. Refine the result in the chat. You can also click **Generate with AI**
   when you edit an existing mapping.
4. Review the generated code or substitutions, test them in the **Test mapping** step, and save.

The mapper never applies an AI result by itself. You review it and save it explicitly, and generated results are
checked for problems that appear as warnings. The following screenshot shows a prompt to generate a list of
substitutions (mapping rules):

![Substitution annotation](../../../resources/image/Dynamic_Mapper_Mapping_Stepper_Substitution_Generate_JSONata.png "Screenshot showing a prompt to generate a list of substitutions (mapping rules).")

// Package reloadruntime ports Pi's reload-runtime extension to Go.
package reloadruntime

import (
	sdk "github.com/MichaelKinsy/PiG/extensions/sdk"
)

func Extension() *sdk.Extension {
	e := sdk.New("reload-runtime")

	e.Command("reload-runtime", "Reload extensions, skills, prompts, themes, and context files",
		func(ctx sdk.Context, _ string) error {
			return ctx.Reload()
		})

	e.RegisterTool(sdk.ToolDefinition{
		Name:        "reload_runtime",
		Label:       "Reload Runtime",
		Description: "Reload extensions, skills, prompts, themes, and context files",
		Parameters:  sdk.Schema{"type": "object", "properties": map[string]any{}},
		Execute: func(ctx sdk.Context, _ map[string]any) (any, error) {
			if err := ctx.SendUserMessage("/reload-runtime", "followUp"); err != nil {
				return nil, err
			}
			return "Queued /reload-runtime as a follow-up command.", nil
		},
	})

	return e
}

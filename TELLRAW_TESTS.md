# Chatglot `/tellraw` tests

Use these commands in a Minecraft 26.2 world with commands enabled. After each
message appears, click Chatglot's translate button and compare the result with
the expected behavior.

## 1. Plain text baseline

```mcfunction
/tellraw @s {text:"Hello world. This is a translation test."}
```

Expected: the complete sentence is translated and no Chatglot marker text is
visible.

## 2. Words split across colors

```mcfunction
/tellraw @s [{text:"Red",color:"red"},{text:" ",color:"white"},{text:"green",color:"green"},{text:" ",color:"white"},{text:"blue",color:"blue"},{text:" words",color:"yellow"}]
```

Expected: translated words remain readable, spaces between words remain, and
the source colors are restored.

## 3. Thirty adjacent styled segments

This is the regression test for markers changed by GAS and spaces inserted
around decorative symbols.

```mcfunction
/tellraw @s [{text:"▬",color:"white"},{text:"▬",color:"red"},{text:"▬",color:"green"},{text:"▬",color:"blue"},{text:"▬",color:"yellow"},{text:"▬",color:"aqua"},{text:"▬",color:"light_purple"},{text:"▬",color:"gold"},{text:"▬",color:"dark_red"},{text:"▬",color:"dark_green"},{text:"▬",color:"dark_blue"},{text:"▬",color:"dark_aqua"},{text:"▬",color:"dark_purple"},{text:"▬",color:"white",bold:true},{text:"▬",color:"red",bold:true},{text:"▬",color:"green",bold:true},{text:"▬",color:"blue",bold:true},{text:"▬",color:"yellow",bold:true},{text:"▬",color:"aqua",bold:true},{text:"▬",color:"light_purple",bold:true},{text:"▬",color:"gold",bold:true},{text:"▬",color:"dark_red",bold:true},{text:"▬",color:"dark_green",bold:true},{text:"▬",color:"dark_blue",bold:true},{text:"▬",color:"dark_aqua",bold:true},{text:"▬",color:"dark_purple",bold:true},{text:"▬",color:"white",italic:true},{text:"▬",color:"red",italic:true},{text:"▬",color:"green",italic:true},{text:"▬",color:"blue",italic:true}]
```

Expected: exactly 30 bars remain adjacent. There must be no marker text or
unexpected gap, and color/bold/italic styles must remain.

## 4. Text decorations

```mcfunction
/tellraw @s [{text:"Bold",color:"gold",bold:true},{text:" / "},{text:"Italic",color:"aqua",italic:true},{text:" / "},{text:"Underline",color:"green",underlined:true},{text:" / "},{text:"Strike",color:"red",strikethrough:true}]
```

Expected: each translated section keeps its original decoration. Separators
and their surrounding spaces remain.

## 5. Adjacent punctuation and symbols

```mcfunction
/tellraw @s [{text:"◆",color:"red"},{text:"◇",color:"gold"},{text:"→",color:"yellow"},{text:"←",color:"green"},{text:"[",color:"aqua"},{text:"]",color:"blue"},{text:"(",color:"light_purple"},{text:")",color:"dark_purple"}]
```

Expected: `◆◇→←[]()` remains contiguous with no spaces inserted between
symbols.

## 6. Intentional spaces around symbols

```mcfunction
/tellraw @s [{text:"A",color:"white"},{text:" ◆ ",color:"aqua"},{text:"B",color:"white"},{text:" ◇ ",color:"yellow"},{text:"C",color:"white"}]
```

Expected: the source spaces surrounding `◆` and `◇` remain. This guards
against removing intentional whitespace while fixing GAS-inserted whitespace.

## 7. Speaker prefix

```mcfunction
/tellraw @s [{text:"<Alice> ",color:"gray"},{text:"Can you meet me at the village?",color:"white"}]
```

Expected: `<Alice> ` is shown once and is not translated; only the message body
is translated.

## 8. Unicode, numbers, and punctuation

```mcfunction
/tellraw @s [{text:"Version 2.0 — café, naïve, emoji 🍎: "},{text:"Meet at X=120, Y=64, Z=-35!",color:"aqua"}]
```

Expected: the sentence is translated without corrupting accented characters,
the emoji, version number, coordinates, or punctuation.

## 9. Explicit line break

```mcfunction
/tellraw @s [{text:"First line: hello",color:"yellow"},{text:"\n"},{text:"Second line: goodbye",color:"aqua"}]
```

Expected: both lines are translated, the line break remains, and each line
keeps its color.

## 10. Click and hover events

```mcfunction
/tellraw @s {text:"Translate this interactive message",color:"aqua",underlined:true,click_event:{action:"suggest_command",command:"/say interaction preserved"},hover_event:{action:"show_text",value:{text:"Hover event preserved",color:"yellow"}}}
```

Expected: the translated text remains aqua and underlined. Hovering displays
the tooltip, and clicking inserts `/say interaction preserved` into chat.

## Quick pass criteria

- No output contains `[[CGT_`, `[[/CGT_`, or a damaged variation such as
  `CG T_`.
- No unexpected spaces appear between adjacent decorative symbols.
- Normal word spaces and intentional source spaces remain.
- Colors, decorations, click events, and hover events remain attached to their
  translated sections.

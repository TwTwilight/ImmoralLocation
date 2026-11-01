docs(readme): two-column screenshot table, new icon in the header

The eight screenshots were two rows of four at width=165: at that size the UI
text is unreadable, and the row wraps awkwardly on narrow screens. Put them in a
centred HTML table instead - two columns at width=340, four rows - so the pairs
stay readable and the whole gallery is one predictable block. A Markdown table
was the first attempt, but it needs a header row and GitHub paints that empty
header as a grey strip above the first images, so the HTML table wins.

The title now carries the new launcher icon (assets/icon.png, 512px render of the
MD3 mark added in de9aec5). Build section, badges and docs links are untouched.

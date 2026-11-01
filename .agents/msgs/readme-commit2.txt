docs(readme): portrait screenshots in a two-column table, icon in the header

The gallery was a three-column grid of raw <img> tags with no width set, so every
cell was laid out at the file's intrinsic 480px and the row overflowed the README
column. Make it a two-column table at width=340 (680px total, fits GitHub's
content column) and keep the main shot as a hero above it, so the four rows are
even: 02/03, 04/05, 06/07, 08/09.

Cells are centred and the alt text stays the Chinese caption. A Markdown table
was the first attempt, but it needs a header row and GitHub paints that empty
header as a grey strip above the first images, so the HTML table wins.

The title now carries the new launcher icon (assets/icon.png, 512px render of the
MD3 mark added in 00846c9). Build section, badge and docs links are untouched.

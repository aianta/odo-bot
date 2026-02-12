package ca.ualberta.odobot;

import ca.ualberta.odobot.common.RobulaPlus;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.NodeVisitor;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RobulaPlusTest {

    private static final Logger log = LoggerFactory.getLogger(RobulaPlusTest.class);

    private class RobulaVisitor implements NodeVisitor {

        RobulaPlus robbulaPlus = new RobulaPlus();
        Document document;

        public RobulaVisitor(Document document) {
            this.document = document;
        }

        @Override
        public void head(Node node, int i) {
            if (node instanceof Element){
                Element element = (Element) node;
                try{

                    String robustXpath = robbulaPlus.getRobustXPath(element, this.document);
                    log.info("robustXpath: {}", robustXpath);

                    assertEquals(document.selectXpath(robustXpath).get(0), element);

                }catch (IllegalStateException e){
                    log.error("Couldn't find robust xpath for element: {}", element.tag());
                }

            }
        }
    }


    @Test
    void testRobulaPlus() {

        Document document = Jsoup.parse(sampleHTML);

        document.traverse(new RobulaVisitor(document));

    }


    private static final String sampleHTML = """
            <html>
             <head></head>
             <body>
              <title>New Discussion Topic</title>
              <div role="alert">
               <div>
                <i></i>
               </div>
               <h1>You need to have JavaScript enabled in order to access this site.</h1>
              </div>
              <div id="flash_message_holder"></div>
              <div id="flash_screenreader_holder" role="alert">
               <div id="Alert___1">
                <span></span>
               </div>
              </div>
              <div id="drawer-layout-mount-point">
               <span>
                <div>
                 <div id="drawer-layout-content" role="region">
                  <div>
                   <div id="application">
                    <button type="button"><i></i><span id="mobileHeaderInboxUnreadBadge"></span><span>Global Navigation Menu</span></button>
                    <div></div><a role="button">
                     <div>
                      AST312
                     </div></a>
                    <div></div>
                    <div id="mobile-top-nav-tools-mount-point">
                     <span>
                      <button id="Menu__label___0" type="button"><span><span><span><span>
                           <svg name="IconLti" role="presentation"></svg></span><span>LTI Tool Menu</span></span></span></span></button></span>
                    </div>
                    <button type="button"><i id="mobileHeaderArrowIcon"></i></button>
                    <div></div><a id="skip_navigation_link">Skip To Content</a>
                    <div role="region">
                     <div>
                      <a href="http://localhost:8088/"><span>Dashboard</span></a>
                     </div>
                     <ul id="menu">
                      <li><a id="global_nav_profile_link" role="button">
                        <div>
                         <div>
                          <img src="http://canvas.instructure.com/images/messages/avatar-50.png" alt="Riley Parker">
                         </div><span></span>
                        </div>
                        <div>
                         Account
                        </div></a></li>
                      <li><a id="global_nav_dashboard_link" href="http://localhost:8088/">
                        <div>
                         <svg></svg>
                        </div>
                        <div>
                         Dashboard
                        </div></a></li>
                      <li><a id="global_nav_courses_link" role="button">
                        <div>
                         <svg></svg>
                        </div>
                        <div>
                         Courses
                        </div></a></li>
                      <li><a id="global_nav_groups_link" role="button">
                        <div>
                         <svg></svg>
                        </div>
                        <div>
                         Groups
                        </div></a></li>
                      <li><a id="global_nav_calendar_link">
                        <div>
                         <svg></svg>
                        </div>
                        <div>
                         Calendar
                        </div></a></li>
                      <li><a id="global_nav_conversations_link">
                        <div>
                         <span>
                          <svg></svg></span><span></span>
                        </div>
                        <div>
                         Inbox
                        </div></a></li>
                      <li><a id="global_nav_help_link" role="button">
                        <div role="presentation">
                         <svg></svg><span></span>
                        </div>
                        <div>
                         Help
                        </div></a></li>
                     </ul>
                    </div>
                    <div>
                     <ul>
                      <li><a id="primaryNavToggle" role="button" title="Minimize global navigation">
                        <div>
                         <svg></svg>
                        </div></a></li>
                     </ul>
                    </div>
                    <div id="global_nav_tray_container"></div>
                    <div id="global_nav_tour"></div>
                    <div id="instructure_ajax_error_box">
                     <div>
                      <a>Close</a>
                     </div><iframe id="instructure_ajax_error_result" title="Error"></iframe>
                    </div>
                    <div id="wrapper">
                     <div>
                      <button type="button" id="courseMenuToggle"><i></i></button>
                      <div>
                       <ul>
                        <li><a><span><i title="My Dashboard"><span>My Dashboard</span></i></span></a></li>
                        <li><a><span>AST312</span></a></li>
                       </ul>
                      </div>
                      <div>
                       <div id="top-nav-tools-mount-point">
                        <div></div>
                       </div>
                      </div>
                     </div>
                     <div id="main">
                      <div></div>
                      <div id="left-side">
                       <div id="sticky-container">
                        <ul id="section-tabs">
                         <li><a id="home-link">Home</a></li>
                         <li><a id="announcements-link">Announcements</a></li>
                         <li><a id="assignments-link">Assignments</a></li>
                         <li><a id="discussions-link">Discussions</a></li>
                         <li><a id="grades-link">Grades<b>19</b></a></li>
                         <li><a id="people-link">People</a></li>
                         <li><a id="pages-link">Pages</a></li>
                         <li><a id="syllabus-link">Syllabus</a></li>
                         <li><a id="outcomes-link">Outcomes</a></li>
                         <li><a id="quizzes-link">Quizzes</a></li>
                         <li><a id="modules-link">Modules</a></li>
                        </ul>
                       </div>
                      </div>
                      <div id="not_right_side">
                       <div id="content-wrapper">
                        <div id="content" role="main">
                         <div id="edit_letter_grades_form">
                          <div id="grading_standard_blank">
                           <div>
                            <div>
                             <div>
                              <a title="Find an Existing Grading Scheme"><img alt="Find">Select Another Scheme</a><a title="Edit Grading Scheme Default Grading Scheme"><i></i></a><a title="Remove Grading Scheme Default Grading Scheme"><i></i></a>
                             </div><strong>Default Grading Scheme</strong>
                             <div>
                              <label>Scheme Name:</label><input type="text" id="grading_standard_title" name="grading_standard[title]" value="Default Grading Scheme">
                             </div>
                            </div>
                            <table>
                             <caption>
                              Current grading scheme for this assignment
                             </caption>
                             <thead>
                              <tr>
                               <th id="name_header">Name:</th>
                               <th colspan="3">
                                <div>
                                 Range:
                                </div>
                                <div></div></th>
                              </tr>
                             </thead>
                             <tbody>
                              <tr>
                               <td colspan="4"><a>insert here</a></td>
                              </tr>
                              <tr>
                               <td>
                                <div>
                                 <input type="text" name="grading_standard[standard_data][scheme_0][name]" value="A">
                                </div>
                                <div>
                                 A
                                </div></td>
                               <td>
                                <div>
                                 <span>100</span>%
                                </div>
                                <div>
                                 <span>100</span>%
                                </div></td>
                               <td>
                                <div>
                                 <span>to</span><input type="text" title="Lower limit of range" name="grading_standard[standard_data][scheme_0][value]" value="94.0">%
                                </div>
                                <div>
                                 <span>to</span><span>94.0</span>%
                                </div></td>
                               <td><a title="Remove row"><i></i></a></td>
                              </tr>
                              <tr>
                               <td colspan="4"><a>insert here</a></td>
                              </tr>
                              <tr>
                               <td>
                                <div>
                                 <input type="text" name="grading_standard[standard_data][scheme_1][name]" value="A-">
                                </div>
                                <div>
                                 A-
                                </div></td>
                               <td>
                                <div>
                                 <span>94.0</span>%
                                </div>
                                <div>
                                 <span>&lt; 94.0</span>%
                                </div></td>
                               <td>
                                <div>
                                 <span>to</span><input type="text" title="Lower limit of range" name="grading_standard[standard_data][scheme_1][value]" value="90.0">%
                                </div>
                                <div>
                                 <span>to</span><span>90.0</span>%
                                </div></td>
                               <td><a title="Remove row"><i></i></a></td>
                              </tr>
                              <tr>
                               <td colspan="4"><a>insert here</a></td>
                              </tr>
                              <tr>
                               <td>
                                <div>
                                 <input type="text" name="grading_standard[standard_data][scheme_2][name]" value="B+">
                                </div>
                                <div>
                                 B+
                                </div></td>
                               <td>
                                <div>
                                 <span>90.0</span>%
                                </div>
                                <div>
                                 <span>&lt; 90.0</span>%
                                </div></td>
                               <td>
                                <div>
                                 <span>to</span><input type="text" title="Lower limit of range" name="grading_standard[standard_data][scheme_2][value]" value="87.0">%
                                </div>
                                <div>
                                 <span>to</span><span>87.0</span>%
                                </div></td>
                               <td><a title="Remove row"><i></i></a></td>
                              </tr>
                              <tr>
                               <td colspan="4"><a>insert here</a></td>
                              </tr>
                              <tr>
                               <td>
                                <div>
                                 <input type="text" name="grading_standard[standard_data][scheme_3][name]" value="B">
                                </div>
                                <div>
                                 B
                                </div></td>
                               <td>
                                <div>
                                 <span>87.0</span>%
                                </div>
                                <div>
                                 <span>&lt; 87.0</span>%
                                </div></td>
                               <td>
                                <div>
                                 <span>to</span><input type="text" title="Lower limit of range" name="grading_standard[standard_data][scheme_3][value]" value="84.0">%
                                </div>
                                <div>
                                 <span>to</span><span>84.0</span>%
                                </div></td>
                               <td><a title="Remove row"><i></i></a></td>
                              </tr>
                              <tr>
                               <td colspan="4"><a>insert here</a></td>
                              </tr>
                              <tr>
                               <td>
                                <div>
                                 <input type="text" name="grading_standard[standard_data][scheme_4][name]" value="B-">
                                </div>
                                <div>
                                 B-
                                </div></td>
                               <td>
                                <div>
                                 <span>84.0</span>%
                                </div>
                                <div>
                                 <span>&lt; 84.0</span>%
                                </div></td>
                               <td>
                                <div>
                                 <span>to</span><input type="text" title="Lower limit of range" name="grading_standard[standard_data][scheme_4][value]" value="80.0">%
                                </div>
                                <div>
                                 <span>to</span><span>80.0</span>%
                                </div></td>
                               <td><a title="Remove row"><i></i></a></td>
                              </tr>
                              <tr>
                               <td colspan="4"><a>insert here</a></td>
                              </tr>
                              <tr>
                               <td>
                                <div>
                                 <input type="text" name="grading_standard[standard_data][scheme_5][name]" value="C+">
                                </div>
                                <div>
                                 C+
                                </div></td>
                               <td>
                                <div>
                                 <span>80.0</span>%
                                </div>
                                <div>
                                 <span>&lt; 80.0</span>%
                                </div></td>
                               <td>
                                <div>
                                 <span>to</span><input type="text" title="Lower limit of range" name="grading_standard[standard_data][scheme_5][value]" value="77.0">%
                                </div>
                                <div>
                                 <span>to</span><span>77.0</span>%
                                </div></td>
                               <td><a title="Remove row"><i></i></a></td>
                              </tr>
                              <tr>
                               <td colspan="4"><a>insert here</a></td>
                              </tr>
                              <tr>
                               <td>
                                <div>
                                 <input type="text" name="grading_standard[standard_data][scheme_6][name]" value="C">
                                </div>
                                <div>
                                 C
                                </div></td>
                               <td>
                                <div>
                                 <span>77.0</span>%
                                </div>
                                <div>
                                 <span>&lt; 77.0</span>%
                                </div></td>
                               <td>
                                <div>
                                 <span>to</span><input type="text" title="Lower limit of range" name="grading_standard[standard_data][scheme_6][value]" value="74.0">%
                                </div>
                                <div>
                                 <span>to</span><span>74.0</span>%
                                </div></td>
                               <td><a title="Remove row"><i></i></a></td>
                              </tr>
                              <tr>
                               <td colspan="4"><a>insert here</a></td>
                              </tr>
                              <tr>
                               <td>
                                <div>
                                 <input type="text" name="grading_standard[standard_data][scheme_7][name]" value="C-">
                                </div>
                                <div>
                                 C-
                                </div></td>
                               <td>
                                <div>
                                 <span>74.0</span>%
                                </div>
                                <div>
                                 <span>&lt; 74.0</span>%
                                </div></td>
                               <td>
                                <div>
                                 <span>to</span><input type="text" title="Lower limit of range" name="grading_standard[standard_data][scheme_7][value]" value="70.0">%
                                </div>
                                <div>
                                 <span>to</span><span>70.0</span>%
                                </div></td>
                               <td><a title="Remove row"><i></i></a></td>
                              </tr>
                              <tr>
                               <td colspan="4"><a>insert here</a></td>
                              </tr>
                              <tr>
                               <td>
                                <div>
                                 <input type="text" name="grading_standard[standard_data][scheme_8][name]" value="D+">
                                </div>
                                <div>
                                 D+
                                </div></td>
                               <td>
                                <div>
                                 <span>70.0</span>%
                                </div>
                                <div>
                                 <span>&lt; 70.0</span>%
                                </div></td>
                               <td>
                                <div>
                                 <span>to</span><input type="text" title="Lower limit of range" name="grading_standard[standard_data][scheme_8][value]" value="67.0">%
                                </div>
                                <div>
                                 <span>to</span><span>67.0</span>%
                                </div></td>
                               <td><a title="Remove row"><i></i></a></td>
                              </tr>
                              <tr>
                               <td colspan="4"><a>insert here</a></td>
                              </tr>
                              <tr>
                               <td>
                                <div>
                                 <input type="text" name="grading_standard[standard_data][scheme_9][name]" value="D">
                                </div>
                                <div>
                                 D
                                </div></td>
                               <td>
                                <div>
                                 <span>67.0</span>%
                                </div>
                                <div>
                                 <span>&lt; 67.0</span>%
                                </div></td>
                               <td>
                                <div>
                                 <span>to</span><input type="text" title="Lower limit of range" name="grading_standard[standard_data][scheme_9][value]" value="64.0">%
                                </div>
                                <div>
                                 <span>to</span><span>64.0</span>%
                                </div></td>
                               <td><a title="Remove row"><i></i></a></td>
                              </tr>
                              <tr>
                               <td colspan="4"><a>insert here</a></td>
                              </tr>
                              <tr>
                               <td>
                                <div>
                                 <input type="text" name="grading_standard[standard_data][scheme_10][name]" value="D-">
                                </div>
                                <div>
                                 D-
                                </div></td>
                               <td>
                                <div>
                                 <span>64.0</span>%
                                </div>
                                <div>
                                 <span>&lt; 64.0</span>%
                                </div></td>
                               <td>
                                <div>
                                 <span>to</span><input type="text" title="Lower limit of range" name="grading_standard[standard_data][scheme_10][value]" value="61.0">%
                                </div>
                                <div>
                                 <span>to</span><span>61.0</span>%
                                </div></td>
                               <td><a title="Remove row"><i></i></a></td>
                              </tr>
                              <tr>
                               <td colspan="4"><a>insert here</a></td>
                              </tr>
                              <tr>
                               <td>
                                <div>
                                 <input type="text" name="grading_standard[standard_data][scheme_11][name]" value="F">
                                </div>
                                <div>
                                 F
                                </div></td>
                               <td>
                                <div>
                                 <span>61.0</span>%
                                </div>
                                <div>
                                 <span>&lt; 61.0</span>%
                                </div></td>
                               <td>
                                <div>
                                 <span>to</span><input type="text" title="Lower limit of range" name="grading_standard[standard_data][scheme_11][value]" value="0.0">%
                                </div>
                                <div>
                                 <span>to</span><span>0.0</span>%
                                </div></td>
                               <td><a title="Remove row"><i></i></a></td>
                              </tr>
                              <tr>
                               <td colspan="4"><a>insert here</a></td>
                              </tr>
                              <tr>
                               <td>
                                <div>
                                 <input type="text" name="grading_standard[standard_data][scheme_blank][name]" value="">
                                </div>
                                <div></div></td>
                               <td>
                                <div>
                                 <span>to</span><span></span>%
                                </div>
                                <div>
                                 <span>to</span><span></span>%
                                </div></td>
                               <td>
                                <div>
                                 <input type="text" name="grading_standard[standard_data][scheme_blank][value]" value="">%
                                </div>
                                <div>
                                 <span></span>%
                                </div></td>
                               <td><a><i></i></a></td>
                              </tr>
                             </tbody>
                            </table>
                            <div>
                             <a>manage grading schemes</a>
                             <button type="button">Done</button>
                            </div>
                            <div>
                             <button type="button">Cancel</button>
                             <button type="button">Save</button>
                            </div>
                           </div>
                           <div>
                            <a></a>
                            <div>
                             Loading Grading Schemes...
                            </div>
                            <table>
                             <tbody>
                              <tr>
                               <td>
                                <ul>
                                 <li><a><span>Some standard</span><span></span></a>
                                  <div>
                                   Full name, smaller text
                                  </div></li>
                                </ul></td>
                               <td>
                                <div>
                                 <div>
                                  <span></span>
                                  <div>
                                   <div>
                                    <a><b></b></a><span></span>
                                   </div>
                                   <div></div>
                                  </div>
                                  <div></div>
                                  <div>
                                   <table>
                                    <tbody>
                                     <tr>
                                      <td></td>
                                      <td><span></span>%</td>
                                      <td>to</td>
                                      <td><span></span>%</td>
                                     </tr>
                                    </tbody>
                                   </table>
                                  </div>
                                  <button type="button">Use This Grading Scheme</button>
                                 </div>
                                </div></td>
                              </tr>
                             </tbody>
                            </table>
                            <div>
                             <a>Cancel</a>
                            </div>
                           </div><a></a><textarea id="default_grading_standard_data">[["A",0.94],["A-",0.9],["B+",0.87],["B",0.84],["B-",0.8],["C+",0.77],["C",0.74],["C-",0.7],["D+",0.67],["D",0.64],["D-",0.61],["F",0.0]]</textarea>
                          </div><a></a><a id="update_grading_standard_url"></a>
                         </div>
                         <form>
                          <h1>New Discussion</h1>
                          <div id="discussion-edit-view">
                           <div id="discussion-edit-header">
                            <ul id="discussion-edit-header-tabs" role="tablist">
                             <li role="tab"><a id="details_link" role="presentation">Details</a></li>
                             <li role="tab"><a id="conditional_release_link" role="presentation">Mastery Paths</a></li><span id="discussion-edit-header-spacer"></span><span id="topic-draft-state"><i></i>Not Published</span>
                            </ul>
                            <div id="discussion-details-tab" role="tabpanel">
                             <span id="announcement-alert-holder"></span>
                             <fieldset>
                              <div>
                               <label>Topic Title</label><input type="text" id="discussion-title" name="title" placeholder="Topic Title" value="">
                              </div>
                              <div>
                               <div></div>
                               <div id="tinymce-parent-of-discussion-topic-message2">
                                <div>
                                 <div>
                                  <button id="show-on-focus-btn-discussion-topic-message2" type="button"><span><span><span><span>
                                       <svg name="IconKeyboardShortcuts" role="presentation"></svg></span><span>View keyboard shortcuts</span></span></span></span></button>
                                 </div>
                                 <div></div>
                                 <div>
                                  <textarea id="discussion-topic-message2" name="message"></textarea>
                                  <div role="document">
                                   <div>
                                    <div>
                                     <div role="menubar">
                                      <button role="menuitem" type="button"><span>Edit</span>
                                       <div>
                                        <svg></svg>
                                       </div></button>
                                      <button role="menuitem" type="button"><span>View</span>
                                       <div>
                                        <svg></svg>
                                       </div></button>
                                      <button role="menuitem" type="button"><span>Insert</span>
                                       <div>
                                        <svg></svg>
                                       </div></button>
                                      <button role="menuitem" type="button"><span>Format</span>
                                       <div>
                                        <svg></svg>
                                       </div></button>
                                      <button role="menuitem" type="button"><span>Tools</span>
                                       <div>
                                        <svg></svg>
                                       </div></button>
                                      <button role="menuitem" type="button"><span>Table</span>
                                       <div>
                                        <svg></svg>
                                       </div></button>
                                     </div>
                                     <div role="group">
                                      <div role="group">
                                       <div title="Styles" role="toolbar">
                                        <button title="Font sizes" type="button"><span>12pt</span>
                                         <div>
                                          <svg></svg>
                                         </div></button>
                                        <button title="Blocks" type="button"><span>Paragraph</span>
                                         <div>
                                          <svg></svg>
                                         </div></button>
                                       </div>
                                       <div title="Formatting" role="toolbar">
                                        <button title="Bold" type="button"><span>
                                          <svg></svg></span></button>
                                        <button title="Italic" type="button"><span>
                                          <svg></svg></span></button>
                                        <button title="Underline" type="button"><span>
                                          <svg></svg></span></button>
                                        <div title="Text color" role="button">
                                         <span role="presentation"><span>
                                          <svg></svg></span></span><span role="presentation">
                                          <svg></svg></span><span id="aria_6153279361651768597487979">To open the popup, press Shift+Enter</span>
                                        </div>
                                        <div title="Background color" role="button">
                                         <span role="presentation"><span>
                                          <svg></svg></span></span><span role="presentation">
                                          <svg></svg></span><span id="aria_988147071671768597487981">To open the popup, press Shift+Enter</span>
                                        </div>
                                        <button title="Superscript and Subscript" type="button"><span>
                                          <svg></svg></span>
                                         <div>
                                          <svg></svg>
                                         </div></button>
                                       </div>
                                       <div title="Content" role="toolbar">
                                        <button title="Links" type="button"><span>
                                          <svg></svg></span>
                                         <div>
                                          <svg></svg>
                                         </div></button>
                                        <button title="Images" type="button"><span>
                                          <svg></svg></span>
                                         <div>
                                          <svg></svg>
                                         </div></button>
                                        <button title="Record/Upload Media" type="button"><span>
                                          <svg></svg></span>
                                         <div>
                                          <svg></svg>
                                         </div></button>
                                        <button title="Documents" type="button"><span>
                                          <svg></svg></span>
                                         <div>
                                          <svg></svg>
                                         </div></button>
                                       </div>
                                       <div title="Alignment and Lists" role="toolbar">
                                        <button title="Align" type="button"><span>
                                          <svg></svg></span>
                                         <div>
                                          <svg></svg>
                                         </div></button>
                                        <div title="Ordered and Unordered Lists" role="button">
                                         <span role="presentation"><span>
                                          <svg></svg></span></span><span role="presentation">
                                          <svg></svg></span><span id="aria_203238811691768597487994">To open the popup, press Shift+Enter</span>
                                        </div>
                                        <button title="Increase Indent" type="button"><span>
                                          <svg></svg></span>
                                         <div>
                                          <svg></svg>
                                         </div></button>
                                       </div>
                                       <div title="Miscellaneous" role="toolbar">
                                        <button title="Clear formatting" type="button"><span>
                                          <svg></svg></span></button>
                                        <button title="Table" type="button"><span>
                                          <svg></svg></span>
                                         <div>
                                          <svg></svg>
                                         </div></button>
                                        <button title="Insert Math Equation" type="button"><span>
                                          <svg></svg></span></button>
                                        <button title="Embed" type="button"><span>
                                          <svg></svg></span></button>
                                       </div>
                                      </div>
                                      <div role="group"></div>
                                     </div>
                                     <div></div>
                                    </div>
                                    <div>
                                     <div>
                                      <iframe id="discussion-topic-message2_ifr" title="Rich Text Area. Press ALT+F8 for Rich Content Editor shortcuts."></iframe>
                                     </div>
                                     <div role="complementary">
                                      <div>
                                       <div></div>
                                      </div>
                                     </div>
                                    </div>
                                   </div>
                                   <div></div>
                                  </div>
                                 </div><span id="discussion-topic-message2_statusbar"><span><span><span><span>p</span></span></span></span><span role="toolbar" title="Editor Status Bar"><span>
                                    <button title="View keyboard shortcuts" type="button"><span><span><span><span>
                                         <svg name="IconKeyboardShortcuts" role="presentation"></svg></span><span>View keyboard shortcuts</span></span></span></span></button>
                                    <button title="Accessibility Checker" type="button"><span><span><span><span>
                                         <svg name="IconA11y" role="presentation"></svg></span><span>Accessibility Checker</span></span></span></span></button></span>
                                   <div></div><span>
                                    <button title="View word and character counts" type="button"><span><span>19 words</span></span></button></span>
                                   <div></div><span>
                                    <button title="Click or shift-click for the html editor." type="button"><span><span><span><span>
                                         <svg role="presentation"></svg></span><span>Switch to the html editor</span></span></span></span></button><span id="edit-button-desc">The pretty html editor is not keyboard accessible. Press Shift O to open the raw html editor.</span></span>
                                   <button title="Fullscreen" type="button"><span><span><span><span>
                                        <div>
                                         <svg name="IconFullScreen" role="presentation"></svg>
                                        </div></span><span>Fullscreen</span></span></span></span></button><span title="Resize" role="button"><span>
                                     <svg name="IconDragHandle" role="presentation"></svg></span></span></span></span>
                                </div>
                               </div>
                              </div>
                             </fieldset>
                             <fieldset>
                              <div id="sections_autocomplete_root">
                               <span><input name="specific_sections" type="hidden" value="all"><span><label><span>Post to</span><span><span><span>
                                     <button type="button" title="Remove All Sections"><span>All Sections</span>
                                      <svg name="IconX" role="presentation"></svg></button><span><input id="Select___0" role="combobox" type="text" value=""><span><span>
                                        <svg name="IconArrowOpenDown" role="presentation"></svg></span></span></span></span></span></span></label><span id="Selectable___0-description">Type or use arrow keys to navigate. Multiple selections are allowed.</span><span><span></span></span></span></span>
                              </div>
                              <div>
                               <label>Attachment</label>
                               <div>
                                <input type="file" name="attachment" id="discussion_attachment_uploaded_data">
                               </div>
                              </div>
                             </fieldset>
                             <fieldset>
                              <legend>Options</legend>
                              <div id="discussion_form_options">
                               <div>
                                <span><span><span><span><input name="require_initial_post" type="hidden" value="0">
                                    <div>
                                     <div>
                                      <input name="require_initial_post" id="require_initial_post" type="checkbox" value="1"><label><span><span></span><span>Users must post before seeing replies</span></span></label>
                                     </div>
                                    </div></span><span><input name="allow_rating" type="hidden" value="0"><input name="only_graders_can_rate" type="hidden" value="0">
                                    <div>
                                     <div>
                                      <input name="allow_rating" id="Checkbox___1" type="checkbox" value="1"><label><span><span></span><span>Allow liking</span></span></label>
                                     </div>
                                    </div></span></span></span></span>
                               </div>
                              </div>
                             </fieldset>
                             <div id="sections_groups_not_allowed_root">
                              <span>
                               <div>
                                <div>
                                 <svg name="IconInfoBorderless" role="presentation"></svg>
                                </div>
                                <div>
                                 Grading and Groups are not supported in Anonymous Discussions.
                                </div>
                               </div></span>
                             </div>
                             <div id="group_category_options"></div>
                             <div id="availability_options">
                              <fieldset>
                               <div>
                                <label>Available From</label><label id="discussion_topic_available_from_accessible_label">Discussion Topic will be available starting at Format Like YYYY-MM-DD hh:mm</label>
                                <div>
                                 <div>
                                  <input type="text" name="delayed_post_at" id="delayed_post_at" value="" title="YYYY-MM-DD hh:mm">
                                  <button type="button"><i></i></button>
                                 </div>
                                 <div></div>
                                </div>
                               </div>
                              </fieldset>
                              <fieldset>
                               <div>
                                <label>Until</label><label id="discussion_topic_available_until_accessible_label">Discussion Topic will be available until Format Like YYYY-MM-DD hh:mm</label>
                                <div>
                                 <div>
                                  <input type="text" name="lock_at" id="lock_at" value="" title="YYYY-MM-DD hh:mm">
                                  <button type="button"><i></i></button>
                                 </div>
                                 <div></div>
                                </div>
                               </div>
                              </fieldset>
                             </div>
                            </div>
                            <div id="mastery-paths-editor" role="tabpanel">
                             <div id="conditional-release-target">
                              <div id="canvas-conditional-release-editor">
                               <div>
                                <span>
                                 <p role="alert"></p></span>
                                <div>
                                 <div>
                                  <span>
                                   <h2>Scoring range 100% to 70%</h2></span>
                                  <div>
                                   <div>
                                    <div>
                                     <span>Top Bound</span><span title="Top Bound">100%</span>
                                    </div>
                                   </div>
                                   <div>
                                    <button type="button">+</button>
                                    <div>
                                     <div>
                                      <span>
                                       <h3>Assignment set 1</h3></span>
                                     </div>
                                    </div>
                                   </div>
                                   <div>
                                    <div>
                                     <span><label>Cutoff Points</label></span><label><span><span><input id="j28kn6otu" title="Cutoff Points" type="text" value="70%"></span></span></label>
                                    </div>
                                   </div>
                                  </div>
                                 </div>
                                 <div>
                                  <span>
                                   <h2>Scoring range 70% to 40%</h2></span>
                                  <div>
                                   <div></div>
                                   <div>
                                    <button type="button">+</button>
                                    <div>
                                     <div>
                                      <span>
                                       <h3>Assignment set 1</h3></span>
                                     </div>
                                    </div>
                                   </div>
                                   <div>
                                    <div>
                                     <span><label>Cutoff Points</label></span><label><span><span><input id="qij3i00nb" title="Cutoff Points" type="text" value="40%"></span></span></label>
                                    </div>
                                   </div>
                                  </div>
                                 </div>
                                 <div>
                                  <span>
                                   <h2>Scoring range 40% to 0%</h2></span>
                                  <div>
                                   <div></div>
                                   <div>
                                    <button type="button">+</button>
                                    <div>
                                     <div>
                                      <span>
                                       <h3>Assignment set 1</h3></span>
                                     </div>
                                    </div>
                                   </div>
                                   <div>
                                    <div>
                                     <span>Lower Bound</span><span title="Lower Bound">0%</span>
                                    </div>
                                   </div>
                                  </div>
                                 </div>
                                </div>
                               </div>
                              </div>
                             </div>
                            </div>
                           </div>
                           <div id="assignment_external_tools">
                            <div></div>
                           </div>
                           <div id="edit_discussion_form_buttons">
                            <button type="button">Cancel</button>
                            <button type="submit">Save</button>
                           </div>
                          </div>
                         </form>
                        </div>
                       </div>
                       <div id="right-side-wrapper"></div>
                      </div>
                     </div>
                    </div>
                    <div></div>
                    <div id="aria_alerts" role="alert"></div>
                    <div id="StudentTray__Container"></div>
                    <div id="react-router-portals"></div><iframe name="post_message_forwarding" title="post_message_forwarding" id="post_message_forwarding"></iframe>
                   </div>
                  </div>
                 </div>
                </div></span>
              </div>
              <div id="nav-tray-portal"></div>
              <div></div>
              <div id="ui-datepicker-div"></div>
              <div></div>
              <div id="tinyaux-discussion-topic-message2"></div>
             </body>
            </html>
            """;

}
